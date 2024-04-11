package zhevlakov.telegram.handler.component

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import zhevlakov.telegram.handler.bot.Handler
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import org.bson.Document
import org.springframework.beans.factory.annotation.Value

@Component
class Startup : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Value("\${telegram.bot.token}")
    lateinit var telegramBotToken: String

    @Value("\${mongo.uri}")
    lateinit var mongoUri: String

    @Value("\${thread.count}")
    var threadCount: Int = 10

    @Value("\${storage.path}")
    lateinit var storagePath: String

    override fun run(args: ApplicationArguments?) {
        try {
            val collection: MongoCollection<Document>
            val mongoClient: MongoClient = MongoClients.create(mongoUri)
            val database = mongoClient.getDatabase("watermark")
            collection = database.getCollection("counter")
            val botsApplication = TelegramBotsLongPollingApplication()
            val handler = Handler(collection, telegramBotToken, threadCount, storagePath)
            botsApplication.registerBot(telegramBotToken, handler)
        } catch (e: TelegramApiException) {
            logger.error(e.message, e)
        }
    }
}