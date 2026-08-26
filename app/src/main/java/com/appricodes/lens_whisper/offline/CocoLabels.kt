package com.appricodes.lens_whisper.offline

/**
 * The standard 80-class COCO label set, in the exact index order every COCO-trained detector
 * (including the bundled YOLOv8n-seg — see [OfflineSegmentationDetector]) uses for its class-score
 * output. This order is a de facto standard across the whole object-detection ecosystem and has
 * been stable since YOLOv3; it is not something the model file itself encodes, so it has to be
 * kept in the app as a plain array indexed by class id.
 */
object CocoLabels {
    val NAMES = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )
}
