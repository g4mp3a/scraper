package com.scraper.cdc.integration

import com.google.api.gax.core.CredentialsProvider
import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.FixedTransportChannelProvider
import com.google.auth.Credentials
import com.google.cloud.pubsub.v1.Publisher
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.cloud.pubsub.v1.TopicAdminSettings
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings
import com.google.pubsub.v1.ProjectSubscriptionName
import com.google.pubsub.v1.PullRequest
import com.google.pubsub.v1.PushConfig
import com.google.pubsub.v1.TopicName
import com.scraper.cdc.service.CdcPublisherService
import io.grpc.ManagedChannelBuilder
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import kotlin.use
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "runEmulatorTests", matches = "true")
class CdcPublisherEmulatorIT : BaseCdcIntegrationTest() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupPubSub() {
            val host = "${pubsub.host}:${pubsub.getMappedPort(8085)}"
            val channel = ManagedChannelBuilder.forTarget(host).usePlaintext().build()
            val channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
            val credentialsProvider = NoCredentialsProvider.create()

            val topicName = TopicName.of(PROJECT_ID, TOPIC_ID)
            val subName = ProjectSubscriptionName.of(PROJECT_ID, SUB_ID)

            // Create Topic
            TopicAdminClient.create(
                TopicAdminSettings.newBuilder()
                    .setTransportChannelProvider(channelProvider)
                    .setCredentialsProvider(credentialsProvider).build()
            ).use { it.createTopic(topicName) }

            // Create Subscription
            SubscriptionAdminClient.create(
                SubscriptionAdminSettings.newBuilder()
                    .setTransportChannelProvider(channelProvider)
                    .setCredentialsProvider(credentialsProvider).build()
            ).use {
                it.createSubscription(subName, topicName, PushConfig.getDefaultInstance(), 10)
            }
        }
    }

    @TestConfiguration
    class LocalConfig {
        @Bean
        @Primary
        fun realPublisher(env: Environment): Publisher {
            val host = env.getProperty("spring.cloud.gcp.pubsub.emulator-host")!!
            val topicName = TopicName.of(PROJECT_ID, TOPIC_ID)
            val channel = ManagedChannelBuilder.forTarget(host).usePlaintext().build()

            return Publisher.newBuilder(topicName)
                .setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build()
        }

        @Bean
        fun subscriberStub(env: Environment): GrpcSubscriberStub {
            val host = env.getProperty("spring.cloud.gcp.pubsub.emulator-host")!!
            val channel = ManagedChannelBuilder.forTarget(host).usePlaintext().build()
            return GrpcSubscriberStub.create(
                SubscriberStubSettings.newBuilder()
                    .setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                    .setCredentialsProvider(NoCredentialsProvider.create())
                    .build()
            )
        }

        @Bean
        fun googleCredentialsProvider(): CredentialsProvider {
            return CredentialsProvider { mock(Credentials::class.java) }
        }
    }

    @Autowired
    lateinit var subscriberStub: GrpcSubscriberStub

    @Autowired
    lateinit var cdcPublisherService: CdcPublisherService

    @Test
    fun `should publish to real emulator`() {
        // Start the service manually as it is disabled by default in properties
        cdcPublisherService.start()

        waitForDebezium()

        val uniqueTag = "test-${System.currentTimeMillis()}"
        val payload = """{"search": "direct test", "tag": "$uniqueTag"}"""

        jdbcTemplate.update(
            "INSERT INTO outbox_event (aggregate_type, aggregate_id, type, payload) VALUES ('SCRAPER', 'job-1', 'TYPE', ?::jsonb)",
            payload
        )

        await().atMost(Duration.ofSeconds(15)).until {
            val pullRequest = PullRequest.newBuilder()
                .setSubscription(ProjectSubscriptionName.format(PROJECT_ID, SUB_ID))
                .setMaxMessages(10) // Pull more to find our specific message
                .build()

            val response = subscriberStub.pullCallable().call(pullRequest)
            response.receivedMessagesList.any { it.message.data.toStringUtf8().contains(uniqueTag) }
        }
    }
}
