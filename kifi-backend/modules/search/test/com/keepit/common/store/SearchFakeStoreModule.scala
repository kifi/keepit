package com.keepit.common.store

import com.google.inject.{Singleton, Provides}
import com.keepit.model.{User, SocialUserInfo, NormalizedURI}
import scala.concurrent._
import com.amazonaws.services.s3.model.PutObjectResult
import com.keepit.common.db.{Id, ExternalId}
import scala.util.{Success, Try}
import java.io.File

case class SearchFakeStoreModule() extends FakeStoreModule {

}

