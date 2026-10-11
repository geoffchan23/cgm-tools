package com.geoffchan.glucosewidget.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/** A tile with one big mic button: tap → [LogActivity] straight into voice input. */
class LogTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val device = requestParams.deviceConfiguration
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("log")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(LogActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()
        val layout = PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(
                Text.Builder(this, "Tell Ray").setTypography(Typography.TYPOGRAPHY_CAPTION1).build(),
            )
            .setContent(
                Button.Builder(this, open)
                    .setIconContent(MIC)
                    .setSize(ButtonDefaults.EXTRA_LARGE_SIZE)
                    .setButtonColors(androidx.wear.protolayout.material.ButtonColors(0xFFFF7A45.toInt(), 0xFFFFFFFF.toInt()))
                    .setContentDescription("Log a dose, food or activity by voice")
                    .build(),
            )
            .setSecondaryLabelTextContent(
                Text.Builder(this, "Dose · food · activity").setTypography(Typography.TYPOGRAPHY_CAPTION2).build(),
            )
            .build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile); "tile" }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(
                MIC,
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_mic).build(),
                    )
                    .build(),
            )
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(resources); "resources" }
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val MIC = "mic"
    }
}
