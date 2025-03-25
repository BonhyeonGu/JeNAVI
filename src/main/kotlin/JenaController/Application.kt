package JenaController

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

import java.io.File

@SpringBootApplication
class Application


fun main(args: Array<String>) {
	val runtime = Runtime.getRuntime()
	val maxMemory = runtime.maxMemory() // 최대 힙 메모리 크기
	val totalMemory = runtime.totalMemory() // 현재 할당된 힙 메모리 크기
	val freeMemory = runtime.freeMemory() // 사용 가능한 힙 메모리 크기

	println(File(".").absolutePath)
	println("Max Memory: ${maxMemory / (1024 * 1024)}MB")
	println("Total Memory: ${totalMemory / (1024 * 1024)}MB")
	println("Free Memory: ${freeMemory / (1024 * 1024)}MB")
	println("Start")
	runApplication<Application>(*args)
}
