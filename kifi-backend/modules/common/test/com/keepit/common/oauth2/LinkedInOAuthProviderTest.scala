package com.keepit.common.oauth2

import com.keepit.test.CommonTestInjector
import org.specs2.mutable.Specification

class LinkedInOAuthProviderTest extends Specification with CommonTestInjector {

  "LinkedInOAuthProvider" should {

    "parse api" in {
      val token = OAuth2AccessToken("asdfasdf")
      LinkedInOAuthProvider.api(token) === "https://api.linkedin.com/v1/people/~:(id,first-name,last-name,email-address,formatted-name,picture-urls::(original);secure=true)?format=json&oauth2_access_token=asdfasdf"
    }
  }

}
