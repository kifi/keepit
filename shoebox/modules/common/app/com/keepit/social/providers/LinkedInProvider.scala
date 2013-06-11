package com.keepit.social.providers

import com.keepit.social.LoggingProvider
import play.api.Application

/**
 * A LinkedIn Provider
 */
class LinkedInProvider(application: Application)
    extends securesocial.core.providers.LinkedInProvider(application) with LoggingProvider
