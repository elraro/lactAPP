package eu.elraro.babytracker.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.remote.AccessTokenInterceptor
import eu.elraro.babytracker.data.remote.AccountApi
import eu.elraro.babytracker.data.remote.AuthApi
import eu.elraro.babytracker.data.remote.PLACEHOLDER_BASE_URL
import eu.elraro.babytracker.data.remote.ServerUrlInterceptor
import eu.elraro.babytracker.data.remote.SyncApi
import eu.elraro.babytracker.data.remote.TokenAuthenticator
import eu.elraro.babytracker.data.repository.DeviceName
import eu.elraro.babytracker.data.session.KeystoreCipher
import eu.elraro.babytracker.data.session.SecretCipher
import eu.elraro.babytracker.data.sync.SyncEngine
import eu.elraro.babytracker.data.sync.SyncRequester
import eu.elraro.babytracker.data.sync.SyncRunner
import eu.elraro.babytracker.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** OkHttp client without credentials, for sign-in and token refresh. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PublicClient

/** OkHttp client that sends the access token and refreshes it on 401. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun secretCipher(impl: KeystoreCipher): SecretCipher

    @Binds
    abstract fun syncRequester(impl: SyncScheduler): SyncRequester

    @Binds
    abstract fun syncRunner(impl: SyncEngine): SyncRunner
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun dataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("session") }

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun deviceName(): DeviceName = DeviceName { "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(MAX_DEVICE_NAME) }

    private const val MAX_DEVICE_NAME = 100
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun json(): Json =
        Json {
            // The server may add fields; older app versions must keep working.
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Provides
    @Singleton
    @PublicClient
    fun publicClient(serverUrl: ServerUrlInterceptor): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(serverUrl)
            .build()

    @Provides
    @Singleton
    @AuthenticatedClient
    fun authenticatedClient(
        @PublicClient base: OkHttpClient,
        accessToken: AccessTokenInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient =
        base
            .newBuilder()
            .addInterceptor(accessToken)
            .authenticator(authenticator)
            .build()

    @Provides
    @Singleton
    fun authApi(
        @PublicClient client: OkHttpClient,
        json: Json,
    ): AuthApi = retrofit(client, json).create(AuthApi::class.java)

    @Provides
    @Singleton
    fun accountApi(
        @AuthenticatedClient client: OkHttpClient,
        json: Json,
    ): AccountApi = retrofit(client, json).create(AccountApi::class.java)

    @Provides
    @Singleton
    fun syncApi(
        @AuthenticatedClient client: OkHttpClient,
        json: Json,
    ): SyncApi = retrofit(client, json).create(SyncApi::class.java)

    fun retrofit(
        client: OkHttpClient,
        json: Json,
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 30L
}
