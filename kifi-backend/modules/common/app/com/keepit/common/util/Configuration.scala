package com.keepit.common.util

import java.lang.{ Boolean => JavaBoolean, Long => JavaLong, Double => JavaDouble, Integer => JavaInteger, String => JavaString, Number => JavaNumber }
import java.util.{ List => JavaList }

import com.google.inject.Singleton
import com.keepit.common.logging.Logging

trait Configuration {
  // Inspired by Play.api.Configuration

  def getString(path: String, validValues: Option[Set[String]] = None): Option[String]
  def getInt(path: String): Option[Int]
  def getBoolean(path: String): Option[Boolean]
  def getMilliseconds(path: String): Option[Long]
  def getNanoseconds(path: String): Option[Long]
  def getBytes(path: String): Option[Long]
  def getDouble(path: String): Option[Double]
  def getLong(path: String): Option[Long]
  def getNumber(path: String): Option[Number]
  def getBooleanList(path: String): Option[JavaList[JavaBoolean]]
  def getBytesList(path: String): Option[JavaList[JavaLong]]
  def getDoubleList(path: String): Option[JavaList[JavaDouble]]
  def getIntList(path: String): Option[JavaList[JavaInteger]]
  def getLongList(path: String): Option[JavaList[JavaLong]]
  def getMillisecondsList(path: String): Option[JavaList[JavaLong]]
  def getNanosecondsList(path: String): Option[JavaList[JavaLong]]
  def getNumberList(path: String): Option[JavaList[JavaNumber]]
  def getStringList(path: String): Option[JavaList[JavaString]]
}

@Singleton
class PlayConfiguration extends Configuration with Logging {
  // When common is migrated to `common-play`, and we have a truly independant `common`, move this to `common-play`, leaving Configuration behind

  // Deletgates to Play's configuration if an Application is running.
  // If not, warns to console, and returns `None` for each method.

  import play.api.Play.maybeApplication

  private def warnNotRunning(path: String) = {
    val msg = s"Trying to get key $path, failed. Play app not running."
    try {
      log.warn(msg)
    } catch {
      case ex: Throwable => // most likely Logger couldn't be created
        println(msg)
    }
    None
  }

  private def config = maybeApplication.map(_.configuration)

  def getString(path: String, validValues: Option[Set[String]] = None): Option[String] = {
    config.map(_.getString(path, validValues)).orElse(warnNotRunning(path)).flatten
  }

  def getInt(path: String): Option[Int] = {
    config.map(_.getInt(path)).orElse(warnNotRunning(path)).flatten
  }

  def getBoolean(path: String): Option[Boolean] = {
    config.map(_.getBoolean(path)).orElse(warnNotRunning(path)).flatten
  }

  def getMilliseconds(path: String): Option[Long] = {
    config.map(_.getMilliseconds(path)).orElse(warnNotRunning(path)).flatten
  }

  def getNanoseconds(path: String): Option[Long] = {
    config.map(_.getNanoseconds(path)).orElse(warnNotRunning(path)).flatten
  }

  def getBytes(path: String): Option[Long] = {
    config.map(_.getBytes(path)).orElse(warnNotRunning(path)).flatten
  }

  def getDouble(path: String): Option[Double] = {
    config.map(_.getDouble(path)).orElse(warnNotRunning(path)).flatten
  }

  def getLong(path: String): Option[Long] = {
    config.map(_.getLong(path)).orElse(warnNotRunning(path)).flatten
  }

  def getNumber(path: String): Option[Number] = {
    config.map(_.getNumber(path)).orElse(warnNotRunning(path)).flatten
  }

  def getBooleanList(path: String): Option[JavaList[JavaBoolean]] = {
    config.map(_.getBooleanList(path)).orElse(warnNotRunning(path)).flatten
  }

  def getBytesList(path: String): Option[JavaList[JavaLong]] = {
    config.map(_.getBytesList(path)).orElse(warnNotRunning(path)).flatten
  }

  def getDoubleList(path: String): Option[JavaList[JavaDouble]] = {
    config.map(_.getDoubleList(path)).orElse(warnNotRunning(path)).flatten
  }

  def getIntList(path: String): Option[JavaList[JavaInteger]] = {
    config.map(_.getIntList(path)).orElse(warnNotRunning(path)).flatten
  }

  def getLongList(path: String): Option[JavaList[JavaLong]] = {
    config.map(_.getLongList(path)).orElse(warnNotRunning(path)).flatten
  }

  def getMillisecondsList(path: String): Option[JavaList[JavaLong]] = {
    config.map(_.getMillisecondsList(path)).orElse(warnNotRunning(path)).flatten
  }

  def getNanosecondsList(path: String): Option[JavaList[JavaLong]] = {
    config.map(_.getNanosecondsList(path)).orElse(warnNotRunning(path)).flatten
  }

  def getNumberList(path: String): Option[JavaList[JavaNumber]] = {
    config.map(_.getNumberList(path)).orElse(warnNotRunning(path)).flatten
  }

  def getStringList(path: String): Option[JavaList[JavaString]] = {
    config.map(_.getStringList(path)).orElse(warnNotRunning(path)).flatten
  }

}
