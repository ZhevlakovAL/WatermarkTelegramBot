package zhevlakov.telegram.handler

import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableAutoConfiguration(exclude = [MongoAutoConfiguration::class, MongoRepositoriesAutoConfiguration::class, MongoDataAutoConfiguration::class])
class HandlerApplication

fun main(args: Array<String>) {
	runApplication<HandlerApplication>(*args)
}
