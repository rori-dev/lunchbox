package lunchbox.domain.service

import lunchbox.domain.logic.LunchResolver
import lunchbox.domain.models.LunchOffer
import lunchbox.domain.models.LunchProvider
import lunchbox.repository.LunchOfferRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

/**
 * Aktualisiert die Mittagsangebote eines Anbieters.
 * <p>
 * Der Aufruf erfolgt asynchron.
 */
@Service
class LunchOfferUpdateWorker(
  val repo: LunchOfferRepository,
  val resolvers: List<LunchResolver>
) {

  private val logger = LoggerFactory.getLogger(javaClass)

  @Async
  fun refreshOffersOf(provider: LunchProvider) {
    val offers = resolve(provider)

    val minDay = offers.map { it.day }.min()
      ?: return

    repo.deleteFrom(minDay, provider.id)
    repo.saveAll(offers)
  }

  private fun resolve(provider: LunchProvider): List<LunchOffer> {
    val resolver = resolvers.find { it.provider == provider }
    if (resolver == null) {
      logger.error("no resolver for $provider")
      return emptyList()
    }

    logger.info("start resolving offers for $provider")

    return try {
      val offers = resolver.resolve()
      logger.info("finished resolving offers for $provider")
      offers
    } catch (e: Throwable) {
      logger.error("failed resolving offers for $provider", e)
      emptyList()
    }
  }
}
