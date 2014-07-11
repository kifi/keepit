package com.keepit.scraper

import org.apache.http.conn.ssl.{ SSLConnectionSocketFactory, SSLContexts, TrustStrategy }
import java.security.cert.X509Certificate

object UnsafeSSLSocketFactory {
  private val trustStrategy = new TrustStrategy {
    def isTrusted(chain: Array[X509Certificate], authType: String): Boolean = true // blindly trust
  }

  def apply() = {
    val sslContext = SSLContexts.custom().loadTrustMaterial(null, trustStrategy).build
    new SSLConnectionSocketFactory(sslContext, SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
  }
}
