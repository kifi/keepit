package com.keepit.common.util

import java.lang.{ Boolean => JavaBoolean, Long => JavaLong, Double => JavaDouble, Integer => JavaInteger, String => JavaString, Number => JavaNumber }
import java.util.{ List => JavaList }

import com.google.inject.Singleton

@Singleton
class TestConfiguration(config: Map[String, Any]) extends Configuration {
  // When common is migrated to `common-play`, and we have a truly independant `common`, move this to `common-play`, leaving Configuration behind

  // If a Play app is running, use its configuration. Fall back to manually set configs.

  import play.api.Play.maybeApplication

  private def appConfig = maybeApplication.map(_.configuration)
  private var _config = scala.collection.mutable.HashMap[String, Any]()
  _config ++= config

  private def getByType[T](path: String): Option[T] = {
    _config.get(path).map { c =>
      try {
        Some(c.asInstanceOf[T])
      } catch {
        case ex: Throwable =>
          println(s"Problem casting $path into requested type.")
          None
      }
    }.flatten
  }

  def setConfig(path: String, value: Any): Unit = {
    _config += path -> value
  }

  def getString(path: String, validValues: Option[Set[String]] = None): Option[String] = {
    appConfig.map(_.getString(path, validValues)).flatten.orElse(getByType(path))
  }

  def getInt(path: String): Option[Int] = {
    appConfig.map(_.getInt(path)).flatten.orElse(getByType(path))
  }

  def getBoolean(path: String): Option[Boolean] = {
    appConfig.map(_.getBoolean(path)).flatten.orElse(getByType(path))
  }

  def getMilliseconds(path: String): Option[Long] = {
    appConfig.map(_.getMilliseconds(path)).flatten.orElse(getByType(path))
  }

  def getNanoseconds(path: String): Option[Long] = {
    appConfig.map(_.getNanoseconds(path)).flatten.orElse(getByType(path))
  }

  def getBytes(path: String): Option[Long] = {
    appConfig.map(_.getBytes(path)).flatten.orElse(getByType(path))
  }

  def getDouble(path: String): Option[Double] = {
    appConfig.map(_.getDouble(path)).flatten.orElse(getByType(path))
  }

  def getLong(path: String): Option[Long] = {
    appConfig.map(_.getLong(path)).flatten.orElse(getByType(path))
  }

  def getNumber(path: String): Option[Number] = {
    appConfig.map(_.getNumber(path)).flatten.orElse(getByType(path))
  }

  def getBooleanList(path: String): Option[JavaList[JavaBoolean]] = {
    appConfig.map(_.getBooleanList(path)).flatten.orElse(getByType(path))
  }

  def getBytesList(path: String): Option[JavaList[JavaLong]] = {
    appConfig.map(_.getBytesList(path)).flatten.orElse(getByType(path))
  }

  def getDoubleList(path: String): Option[JavaList[JavaDouble]] = {
    appConfig.map(_.getDoubleList(path)).flatten.orElse(getByType(path))
  }

  def getIntList(path: String): Option[JavaList[JavaInteger]] = {
    appConfig.map(_.getIntList(path)).flatten.orElse(getByType(path))
  }

  def getLongList(path: String): Option[JavaList[JavaLong]] = {
    appConfig.map(_.getLongList(path)).flatten.orElse(getByType(path))
  }

  def getMillisecondsList(path: String): Option[JavaList[JavaLong]] = {
    appConfig.map(_.getMillisecondsList(path)).flatten.orElse(getByType(path))
  }

  def getNanosecondsList(path: String): Option[JavaList[JavaLong]] = {
    appConfig.map(_.getNanosecondsList(path)).flatten.orElse(getByType(path))
  }

  def getNumberList(path: String): Option[JavaList[JavaNumber]] = {
    appConfig.map(_.getNumberList(path)).flatten.orElse(getByType(path))
  }

  def getStringList(path: String): Option[JavaList[JavaString]] = {
    appConfig.map(_.getStringList(path)).flatten.orElse(getByType(path))
  }

}

