package com.keepit.common.amazon

import com.keepit.common.time._
import com.google.inject.{Inject, Singleton}
import com.keepit.common.net.HttpClient

@Singleton
class AmazonInstanceInfo @Inject() (httpClient: HttpClient) {
  private val metadataUrl: String = "http://169.254.169.254/latest/meta-data/"
  lazy val instanceId: String = httpClient.get(metadataUrl + "instance-id").body
  lazy val localHostname: String = httpClient.get(metadataUrl + "local-hostname").body
  lazy val publicHostname: String = httpClient.get(metadataUrl + "public-hostname").body
  lazy val localIp: String = httpClient.get(metadataUrl + "local-ipv4").body
  lazy val publicIp: String = httpClient.get(metadataUrl + "public-ipv4").body
  lazy val instanceType: String = httpClient.get(metadataUrl + "instance-type").body
  lazy val availabilityZone: String = httpClient.get(metadataUrl + "placement/availability-zone").body
  lazy val securityGroups: String = httpClient.get(metadataUrl + "security-groups").body
  lazy val amiId: String = httpClient.get(metadataUrl + "ami-id").body
  lazy val amiLaunchIndex: String = httpClient.get(metadataUrl + "ami-launch-index").body
}
