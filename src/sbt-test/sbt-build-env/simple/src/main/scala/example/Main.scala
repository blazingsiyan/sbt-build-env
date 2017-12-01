package example

import scala.collection.JavaConverters._

object Main extends App {
  val properties = System.getProperties()
  properties.load(getClass.getResourceAsStream("/test.properties"))
  println(s"read property build_env=${properties.getProperty("build_env")}")
}
