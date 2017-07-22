package domain.models

import scala.collection.immutable.TreeSet

sealed abstract class LunchProvider(
  val id: LunchProviderId,
  val name: String,
  val location: Location /*,
    val active: Boolean = true */
) extends Ordered[LunchProvider] {

  override def compare(that: LunchProvider) = this.id - that.id

  override def toString = name
}

object LunchProvider {
  case object SCHWEINESTALL extends LunchProvider(1, "Schweinestall", "Neubrandenburg")
  case object HOTEL_AM_RING extends LunchProvider(2, "Hotel am Ring", "Neubrandenburg" /*, false*/ )
  case object AOK_CAFETERIA extends LunchProvider(3, "AOK Cafeteria", "Neubrandenburg")
  case object SUPPENKULTTOUR extends LunchProvider(4, "Suppenkulttour", "Neubrandenburg")
  case object SALT_N_PEPPER extends LunchProvider(5, "Salt 'n' Pepper", "Berlin Springpfuhl")
  case object GESUNDHEITSZENTRUM extends LunchProvider(6, "Gesundheitszentrum", "Berlin Springpfuhl")
  case object FELDKUECHE extends LunchProvider(7, "Feldküche Karow", "Berlin Springpfuhl")
  case object DAS_KRAUTHOF extends LunchProvider(8, "Das Krauthof", "Neubrandenburg")

  // TODO: improve with macro, see https://github.com/d6y/enumeration-examples & http://underscore.io/blog/posts/2014/09/03/enumerations.html
  val values = TreeSet[LunchProvider](SCHWEINESTALL, HOTEL_AM_RING, AOK_CAFETERIA, SUPPENKULTTOUR, SALT_N_PEPPER, GESUNDHEITSZENTRUM, FELDKUECHE, DAS_KRAUTHOF)
}
