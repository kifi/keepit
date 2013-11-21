package com.keepit.common.store

import play.api.Mode
import play.api.Mode._
import play.api.Play.current
import play.api.Play._

import net.codingwell.scalaguice.ScalaModule

import com.keepit.common.time.Clock
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.{Provider, Provides, Singleton}
import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3}
import com.keepit.search._
import com.keepit.common.analytics._
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.learning.topicmodel._

trait TestStoreModule extends StoreModule {

}

