package com.keepit.scraper

case class ScraperConfig(
  minInterval: Double = 12.0d, //hours
  maxInterval: Double = 1024.0d, //hours
  intervalIncrement: Double = 2.0d, //hours
  intervalDecrement: Double = 1.0d, //hours
  initialBackoff: Double = 1.0d, //hours
  maxBackoff: Double = 1024.0d, //hours
  changeThreshold: Double = 0.05
)
