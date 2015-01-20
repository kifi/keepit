package com.keepit.commander

import org.specs2.mutable.Specification
import com.keepit.commanders.ScraperURISummaryCommander

class ScraperURISummaryCommanderTest extends Specification {
  "ScraperURISummaryCommander.filterImageByUrl" should {

    "accept reasonable image url" in {
      val result = ScraperURISummaryCommander.isValidImageUrl("http://blogs.sacurrent.com/wp-content/uploads/2013/11/beatles-white-album.jpg")
      result === true
    }

    "accept reasonable image url with arguments" in {
      val result = ScraperURISummaryCommander.isValidImageUrl("http://blogs.sacurrent.com/wp-content/uploads/2013/11/beatles-white-album.jpg?test=true&anotherargument=theargument")
      result === true
    }

    "filter likely blank images" in {
      val result = ScraperURISummaryCommander.isValidImageUrl("http://wordpress.com/i/blank.jpg")
      result === false
    }

    "filter likely blank images with arguments" in {
      val result = ScraperURISummaryCommander.isValidImageUrl("http://wordpress.com/i/blank.jpg?m=1383295312g")
      result === false
    }
  }
}
