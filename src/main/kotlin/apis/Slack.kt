package apis

import com.slack.api.Slack
import com.slack.api.model.kotlin_extension.block.dsl.LayoutBlockDsl
import com.slack.api.model.kotlin_extension.block.withBlocks
import com.slack.api.webhook.Payload
import com.slack.api.webhook.Payload.PayloadBuilder
import com.slack.api.webhook.WebhookResponse

/**
 * Slack extensions for Kotlin
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2024 PocketCampus Sàrl
 */

fun Slack.send(url: String, configurePayload: PayloadBuilder.() -> Unit): WebhookResponse? {
    val payload = Payload.builder()
    configurePayload(payload)
    return this.send(url, payload.build())
}

fun PayloadBuilder.blocks(blocksBuilder: LayoutBlockDsl.() -> Unit) {
    this.blocks(withBlocks(blocksBuilder))
}