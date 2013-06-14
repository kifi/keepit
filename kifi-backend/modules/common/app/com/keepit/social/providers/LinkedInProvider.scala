package com.keepit.social.providers

import com.keepit.social.UserIdentityProvider

import play.api.Application

/**
 * A LinkedIn Provider
 */
class LinkedInProvider(application: Application)
    extends securesocial.core.providers.LinkedInProvider(application) with UserIdentityProvider
