package lunchbox.domain.service

import lunchbox.domain.models.LunchProvider
import lunchbox.repository.LunchOfferRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.Schedules
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import javax.annotation.PostConstruct

@Service
class LunchOfferUpdate(
  val repo: LunchOfferRepository,
  val worker: LunchOfferUpdateWorker
) {

  private val logger = LoggerFactory.getLogger(javaClass)

  @PostConstruct // update on startup and ...
  @Schedules(
    Scheduled(cron = "0 0  7 * *   *", zone = "Europe/Berlin"), // every day at 7h and ...
    Scheduled(cron = "0 0 10 * * MON", zone = "Europe/Berlin")  // every monday at 10h
  )
  fun updateOffers() {
    logger.info("starting offer update")

    removeOutdatedOffers()

    for (provider in LunchProvider.values().filter { it.active })
      worker.refreshOffersOf(provider)
  }

  private fun removeOutdatedOffers() {
    repo.deleteBefore(mondayLastWeek())
  }

  private fun mondayLastWeek(): LocalDate {
    val mondayThisWeek = LocalDate.now().with(DayOfWeek.MONDAY)
    return mondayThisWeek.minusWeeks(1)
  }

/*
  private fun resolveAndAddOffersFor(provider: LunchProvider) {
    val job = GlobalScope.async {}
    try {
      job.await()
    } catch (e: Exception) {
      println("Caught ArithmeticException")
    }
  } */
}
