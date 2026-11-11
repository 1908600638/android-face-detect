package com.shizi.face.scan;

import android.graphics.Bitmap;

import com.huawei.hms.mlsdk.MLAnalyzerFactory;
import com.huawei.hms.mlsdk.common.MLFrame;
import com.huawei.hms.mlsdk.face.MLFace;
import com.huawei.hms.mlsdk.face.MLFaceAnalyzer;
import com.huawei.hms.mlsdk.face.MLFaceAnalyzerSetting;
import com.huawei.hms.mlsdk.face.MLFaceEmotion;
import com.huawei.hms.mlsdk.face.MLFaceFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 人脸分析：华为 HMS 的端侧人脸检测，一次给出人脸框、表情、性别、年龄、眼镜、帽子等属性。
 */
public final class FaceEngine implements Scanner {

    private static final String[] EMOTIONS = {
            "微笑", "无表情", "惊讶", "悲伤", "愤怒", "厌恶", "恐惧",
    };

    private static final MLFaceAnalyzerSetting SETTING = new MLFaceAnalyzerSetting.Factory()
            .setKeyPointType(MLFaceAnalyzerSetting.TYPE_KEYPOINTS)
            .setFeatureType(MLFaceAnalyzerSetting.TYPE_FEATURE_EMOTION
                    | MLFaceAnalyzerSetting.TYPE_FEATURE_GENDAR)
            .setShapeType(MLFaceAnalyzerSetting.TYPE_SHAPES)
            .setTracingAllowed(false, MLFaceAnalyzerSetting.MODE_TRACING_FAST)
            .setPerformanceType(MLFaceAnalyzerSetting.TYPE_SPEED)
            .setPoseDisabled(true)
            .create();

    private final MLFaceAnalyzer analyzer =
            MLAnalyzerFactory.getInstance().getFaceAnalyzer(SETTING);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile boolean closed;

    @Override
    public void scan(final Bitmap bitmap, final Callback callback) {
        if (closed) {
            return;
        }
        worker.execute(() -> {
            MLFrame frame = MLFrame.fromBitmap(bitmap);
            analyzer.asyncAnalyseFrame(frame)
                    .addOnSuccessListener(faces -> {
                        if (closed) {
                            return;
                        }
                        List<Detect> detects = new ArrayList<>();
                        for (MLFace face : faces) {
                            detects.add(toDetect(face));
                        }
                        callback.onResult(new ScanOutcome(detects, describe(detects)),
                                BoxPainter.paint(bitmap, detects));
                    })
                    .addOnFailureListener(error -> {
                        if (!closed) {
                            callback.onError(error);
                        }
                    });
        });
    }

    private static Detect toDetect(MLFace face) {
        MLFaceEmotion emotion = face.getEmotions();
        float[] scores = {
                emotion.getSmilingProbability(),
                emotion.getNeutralProbability(),
                emotion.getSurpriseProbability(),
                emotion.getSadProbability(),
                emotion.getAngryProbability(),
                emotion.getDisgustProbability(),
                emotion.getFearProbability(),
        };
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }

        MLFaceFeature feature = face.getFeatures();
        String gender = feature.getSexProbability() < 0.5f ? "男" : "女";
        String note = "年龄 " + feature.getAge() + "，" + gender
                + (feature.getSunGlassProbability() > 0.6f ? "，戴眼镜" : "，没戴眼镜")
                + (feature.getHatProbability() > 0.5f ? "，戴帽子" : "，没戴帽子");

        return new Detect(EMOTIONS[best] + " / " + gender, note, scores[best], face.getBorder());
    }

    private static String describe(List<Detect> detects) {
        if (detects.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < detects.size(); i++) {
            Detect detect = detects.get(i);
            if (i > 0) {
                builder.append('\n').append('\n');
            }
            builder.append(i + 1).append(". ").append(detect.label);
            if (detect.score > 0f) {
                builder.append("   置信度 ")
                        .append(String.format(Locale.US, "%.1f%%", detect.score * 100f));
            }
            builder.append('\n').append(detect.note);
        }
        return builder.toString();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        worker.shutdownNow();
        try {
            analyzer.stop();
        } catch (IOException ignored) {
        }
    }
}
