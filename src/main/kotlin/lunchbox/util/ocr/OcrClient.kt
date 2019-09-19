package lunchbox.util.ocr

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.net.URL
import java.time.Duration

/**
 * Führt Texterkennung (OCR) auf dem übergebenen Bild via OpenOCR aus.
 * <p>
 * OpenOCR wird in der Lunchbox via Docker bereitgestellt.
 */
@Component
class OcrClient(
  @Value("\${external.ocr.server-url:http://openocr:$OCR_SERVER_PORT}")
  val ocrServerUrl: String
) {

  fun doOCR(imageUrl: URL): String {
    val requestBody = mapOf(
      "img_url" to imageUrl,
      "engine" to "tesseract",
      "engine_args" to mapOf("lang" to "deu")
    )

    return WebClient.create("$ocrServerUrl/ocr")
      .post()
      .body(BodyInserters.fromObject(requestBody))
      .retrieve()
      .bodyToMono<String>()
      .retryBackoff(5, Duration.ofSeconds(5), Duration.ofSeconds(60))
      .block() ?: ""
  }
}

const val OCR_SERVER_PORT = 9292
