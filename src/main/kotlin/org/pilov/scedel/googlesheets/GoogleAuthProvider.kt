package org.pilov.scedel.googlesheets

import com.google.api.client.http.HttpRequestInitializer

object GoogleAuthProvider {

    fun createRequestInitializer(): HttpRequestInitializer = GoogleOAuthAuth.createRequestInitializer()
}
