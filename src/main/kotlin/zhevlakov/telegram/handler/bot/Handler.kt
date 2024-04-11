package zhevlakov.telegram.handler.bot

import com.mongodb.client.MongoCollection
import org.bson.Document
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.*
import kotlin.io.path.Path
import kotlin.io.path.name

private data class WatermarkData<T>(val message: Message, var data: T)
private data class FileData<T>(val requestId: UUID, val message: Message, var data: T)

private class HandlerException(message: String) : Exception(message)

class Handler(private val collection: MongoCollection<Document>, telegramBotToken: String, threadCount: Int, private val storagePath: String): LongPollingSingleThreadUpdateConsumer {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private var executor: ExecutorService = Executors.newFixedThreadPool(threadCount)
    private val telegramClient: TelegramClient = OkHttpTelegramClient(telegramBotToken)

    override fun consume(update: Update?) {
        if (update != null && update.hasMessage()) {
            if (update.message.hasText()) {
                handleText(update)
            } else if (update.message.hasPhoto() || update.message.hasVideo()) {
                if (!existFileInDirectory(getWatermarkPath(update.message.chatId).toFile())) {
                    sendMessageAsync(update.message.chatId, "Водяной знак не загружен")
                    return
                }
                if (update.message.hasPhoto()) {
                    handlePhoto(update.message)
                }
                if (update.message.hasVideo()) {
                    handleVideo(update)
                }
            } else if (update.message.hasDocument()) {
                handleDocument(update.message)
            }
        }
    }

    private fun handleText(update: Update) {
        if (update.message.text.equals("/start")) {
            sendMessageAsync(
                update.message.chatId,
                "Через файл загрузите водяной знак. " +
                        "Через фото или видео загрузите ресурс на который надо наложить водяной знак. " +
                        "/reset сбросить водяной знак"
            )
        } else if (update.message.text.equals("/reset")) {
            resetWatermark(update)
        }
    }

    private fun increaseCounter(chatId: Long) {
        val searchQuery = Document()
        searchQuery["chatId"] = chatId
        val cursor = collection.find(searchQuery)
        if (cursor.count() == 0) {
            val document = Document()
            document["chatId"] = chatId
            document["count"] = 1L
            collection.insertOne(document)
        } else {
            val document: Document = cursor.first()!!
            val newDocument = Document()
            newDocument["count"] = document["count"] as Long + 1
            val updateObject = Document()
            updateObject["\$set"] = newDocument
            collection.updateOne(document, updateObject)
        }
    }

    private fun handleVideo(update: Update) {
        CompletableFuture
            .supplyAsync({
                FileData(UUID.randomUUID(), update.message, update.message)
            }, executor)
            .thenApply {
                prepareStorageDirectory(it)
            }
            .thenApply {
                getTelegramFileIdFromVideo(it)
            }
            .thenApply {
                getTelegramFileByFileId(it)
            }
            .thenApply {
                saveSourceFile(it)
            }
            .thenApply {
                putWatermark(it)
            }
            .thenApply {
                sendVideo(it)
            }
            .thenApply {
                clearStorage(it)
            }
            .thenApply {
                increaseCounter(it.message.chatId)
            }
            //отправка данных в монго
            .whenComplete { _, u -> handleException(u) }
    }

    private fun sendVideo(it: FileData<String>): FileData<String> {
        val mediaFile = File(it.data)
        val msg: SendVideo = SendVideo
            .builder()
            .chatId(it.message.chatId)
            .video(InputFile(mediaFile))
            .build()
        telegramClient.execute(msg)
        return FileData(it.requestId, it.message, it.data)
    }

    private fun getTelegramFileIdFromVideo(it: FileData<Path>) =
        FileData(it.requestId, it.message, GetFile(it.message.video.fileId))

    private fun handlePhoto(message: Message) {
        CompletableFuture
            .supplyAsync({
                FileData(UUID.randomUUID(), message, message)
            }, executor)
            .thenApply {
                prepareStorageDirectory(it)
            }
            .thenApply {
                getTelegramFileIdFromPhoto(it)
            }
            .thenApply {
                getTelegramFileByFileId(it)
            }
            .thenApply {
                saveSourceFile(it)
            }
            .thenApply {
                putWatermark(it)
            }
            .thenApply {
                sendPhoto(it)
            }
            .thenApply {
                clearStorage(it)
            }
            .thenApply {
                increaseCounter(it.message.chatId)
            }
            .whenComplete { _, u -> handleException(u) }
    }

    private fun clearStorage(it: FileData<String>): FileData<String> {
        deleteDirectory(getSourcePath(it.message.chatId, it.requestId).toFile())
        deleteDirectory(getProcessedPath(it.message.chatId, it.requestId).toFile())
        return FileData(it.requestId, it.message, it.data)
    }

    private fun sendPhoto(
        it: FileData<String>
    ): FileData<String> {
        val mediaFile = File(it.data)
        val msg: SendPhoto = SendPhoto
            .builder()
            .chatId(it.message.chatId)
            .photo(InputFile(mediaFile))
            .build()
        telegramClient.execute(msg)
        return FileData(it.requestId, it.message, it.data)
    }

    private fun putWatermark(it: FileData<String>): FileData<String> {
        val sourceFilePath = it.data
        val watermarkFilePath = getFirstFileInDirectory(getWatermarkPath(it.message.chatId).toFile())!!.path
        val fileName = getFileName(sourceFilePath)
        val processedFilePath = "${getProcessedPath(it.message.chatId, it.requestId)}${File.separator}$fileName"
        val exitCode = putWatermark(sourceFilePath, watermarkFilePath, processedFilePath)
        if (exitCode != 0) {
            val errorMessage = "Ошибка при наложении водяного знака"
            sendMessage(it.message.chatId, errorMessage)
            throw HandlerException(errorMessage)
        }
        return FileData(it.requestId, it.message, processedFilePath)
    }

    private fun saveSourceFile(it: FileData<org.telegram.telegrambots.meta.api.objects.File>): FileData<String> {
        val fileName = getFileName(it.data.filePath)
        val filePath = "${getSourcePath(it.message.chatId, it.requestId)}${File.separator}$fileName"
        saveFile(it.data, filePath)!!.get()
        return FileData(it.requestId, it.message, filePath)
    }

    private fun getTelegramFileByFileId(it: FileData<GetFile>) =
        FileData(it.requestId, it.message, telegramClient.execute(GetFile(it.data.fileId)))

    private fun getTelegramFileIdFromPhoto(it: FileData<Path>): FileData<GetFile> {
        val maxSizePhoto = it.message.photo.maxByOrNull { photo -> photo.fileSize }
        return FileData(it.requestId, it.message, GetFile(maxSizePhoto!!.fileId))
    }

    private fun prepareStorageDirectory(it: FileData<Message>): FileData<Path> {
        prepareDirectory(getSourcePath(it.message.chatId, it.requestId))
        val processedPath = prepareDirectory(getProcessedPath(it.message.chatId, it.requestId))
        return FileData(it.requestId, it.message, processedPath)
    }

    private fun putWatermark(
        sourceFilePath: String,
        watermarkFilePath: String?,
        processedFilePath: String
    ): Int {
        val builder = ProcessBuilder()
        builder.command(
            "ffmpeg",
            "-y",
            "-i",
            sourceFilePath,
            "-i",
            watermarkFilePath,
            "-filter_complex",
            "[1]format=bgra,colorchannelmixer=aa=0.4,rotate=0:c=black@0:ow=rotw(0):oh=roth(0)[image1];[0][image1]overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2",
            processedFilePath
        )
        builder.directory(File(System.getProperty("user.home")))
        val process = builder.start()
        BufferedReader(InputStreamReader(process.errorStream)).lines().forEach { line -> logger.info(line) }
        val exitCode = process.waitFor()
        return exitCode
    }

    private fun resetWatermark(update: Update) {
        CompletableFuture
            .supplyAsync({
                WatermarkData(update.message, update.message)
            }, executor)
            .thenApply {
                WatermarkData(it.message, getWatermarkPath(it.message.chatId))
            }
            .thenApply {
                WatermarkData(it.message, prepareDirectory(it.data))
            }
            .thenApply {
                sendMessage(it.message.chatId, "Водяной знак удален")
            }
            .whenComplete { _, u -> handleException(u) }
    }

    private fun sendMessageAsync(chatId: Long, text: String) {
        CompletableFuture
            .supplyAsync({
                sendMessage(chatId, text)
            }, executor)
            .whenComplete { _, u -> handleException(u) }
    }

    private fun handleDocument(message: Message) {
        CompletableFuture
            .supplyAsync ({
                WatermarkData(message, message)
            }, executor)
            .thenApply {
                WatermarkData(it.message, GetFile(it.message.document.fileId))
            }
            .thenApply {
                WatermarkData(it.message, telegramClient.execute(it.data))
            }
            .thenApply {
                val fileName = getFileName(it.data.filePath)
                if (!isPng(fileName)) {
                    val errorMessage = "Расширение файла для водяного знака должно быть в формате PNG"
                    sendMessage(it.message.chatId, errorMessage)
                    throw HandlerException(errorMessage)
                }
                val watermarkPath = getWatermarkPath(it.message.chatId)
                prepareDirectory(watermarkPath)
                val filePath = "$watermarkPath${File.separator}$fileName"
                saveFile(it.data, filePath)!!.get()
                WatermarkData(it.message, it.message)
            }
            .thenApply {
                sendMessage(it.message.chatId, "Водяной знак загружен")
            }
            .whenComplete { _, u -> handleException(u) }
    }

    private fun prepareDirectory(it: Path): Path {
        deleteDirectory(it.toFile())
        return Files.createDirectories(it)
    }

    private fun handleException(u: Throwable) {
        if (u.message != null) {
            logger.error(u.message)
        }
    }

    private fun getFileName(filePath: String) = Path(filePath).fileName.name

    private fun isPng(fileName: String) = fileExtension(fileName).lowercase() == "png"

    private fun fileExtension(fileName: String) = fileName.substringAfterLast(".")

    private fun getWatermarkPath(chatId: Long): Path = 
        Paths.get("$storagePath${chatId}${File.separator}watermark${File.separator}")
    
    private fun getSourcePath(chatId: Long, requestId: UUID): Path = 
        Paths.get("$storagePath${chatId}${File.separator}source${File.separator}${requestId}${File.separator}")
    
    private fun getProcessedPath(chatId: Long, requestId: UUID): Path = 
        Paths.get("$storagePath${chatId}${File.separator}processed${File.separator}${requestId}${File.separator}")

    private fun saveFile(
        file: org.telegram.telegrambots.meta.api.objects.File,
        filePath: String
    ): CompletableFuture<Long>? = telegramClient.downloadFileAsStreamAsync(file).thenApply {
        FileOutputStream(filePath).use { output ->
            it.transferTo(output)
        }
    }

    private fun sendMessage(chatId: Long, text: String): Message? =
        telegramClient.execute(
            SendMessage // Create a message object
                .builder()
                .chatId(chatId)
                .text(text)
                .build()
        )

    private fun deleteDirectory(directory: File) {
        if (directory.exists() && directory.isDirectory) {
            directory.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    deleteDirectory(file)
                } else {
                    file.delete()
                }
            }
            directory.delete()
        }
    }

    private fun existFileInDirectory(directory: File): Boolean {
        if (directory.exists() && directory.isDirectory) {
            directory.listFiles()?.forEach { file ->
                if (file.isFile) {
                    return true
                }
            }
        }
        return false
    }

    private fun getFirstFileInDirectory(directory: File): File? {
        if (directory.exists() && directory.isDirectory) {
            directory.listFiles()?.forEach { file ->
                if (file.isFile) {
                    return file
                }
            }
        }
        return null
    }
}