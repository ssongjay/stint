package dev.stint.app.ui

internal data class FoldContentGeometry(
    val hingeLeftDp: Float,
    val hingeTopDp: Float,
    val validBook: Boolean,
    val validTabletop: Boolean,
)

internal fun resolveFoldContentGeometry(
    posture: FoldPosture,
    contentOriginXDp: Float,
    contentOriginYDp: Float,
    contentWidthDp: Float,
    contentHeightDp: Float,
    minimumPaneDp: Float = 120f,
): FoldContentGeometry {
    val hingeLeft = (posture.hingeLeftDp - contentOriginXDp).coerceAtLeast(0f)
    val hingeTop = (posture.hingeTopDp - contentOriginYDp).coerceAtLeast(0f)
    return FoldContentGeometry(
        hingeLeftDp = hingeLeft,
        hingeTopDp = hingeTop,
        validBook = posture.isBook &&
            hingeLeft > minimumPaneDp &&
            hingeLeft + posture.hingeWidthDp < contentWidthDp - minimumPaneDp,
        validTabletop = posture.isTabletop &&
            hingeTop > minimumPaneDp &&
            hingeTop + posture.hingeHeightDp < contentHeightDp - minimumPaneDp,
    )
}
