package com.keepit.scraper

import org.apache.http.conn.ssl.SSLSocketFactory
import org.apache.http.conn.ssl.TrustStrategy
import java.security.cert.X509Certificate

object UnsafeSSLSocketFactory {
  private val trustStrategy = new TrustStrategy {
    def isTrusted(chain: Array[X509Certificate], authType: String): Boolean = true // blindly trust
  }

  def apply() = new SSLSocketFactory(trustStrategy, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
}
