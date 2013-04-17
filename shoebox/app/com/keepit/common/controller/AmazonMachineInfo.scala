package com.keepit.common.controller

import com.keepit.common.time._
import com.google.inject.{Inject, Singleton}
import com.keepit.common.net.HttpClient

@Singleton
class AmazonMachineInfo @Inject() (httpClient: HttpClient) {
  private val metadataUrl = "http://169.254.169.254/latest/meta-data/"
  lazy val amiId = httpClient.get(metadataUrl + "ami-id").body
  lazy val amiLaunchIndex = httpClient.get(metadataUrl + "ami-launch-index").body
  lazy val localHostname = httpClient.get(metadataUrl + "local-hostname").body
  lazy val publicHostname = httpClient.get(metadataUrl + "public-hostname").body
  lazy val localIp = httpClient.get(metadataUrl + "local-ipv4").body
  lazy val publicIp = httpClient.get(metadataUrl + "public-ipv4").body
  lazy val instanceId = httpClient.get(metadataUrl + "instance-id").body
  lazy val instanceType = httpClient.get(metadataUrl + "instance-type").body
  lazy val availabilityZone = httpClient.get(metadataUrl + "placement/availability-zone").body
  lazy val securityGroups = httpClient.get(metadataUrl + "security-groups").body
}



