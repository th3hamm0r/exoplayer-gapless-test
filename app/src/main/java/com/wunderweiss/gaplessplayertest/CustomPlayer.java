package com.wunderweiss.gaplessplayertest;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import android.os.AsyncTask;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class CustomPlayer extends AsyncTask<Void, Void, Void> {

    private static final String TAG = CustomPlayer.class.getSimpleName();

    /**
     * A multiplication factor to apply to the minimum buffer size requested by the underlying
     * {@link android.media.AudioTrack}.
     */
    private static final int BUFFER_MULTIPLICATION_FACTOR = 4;

    /**
     * A minimum length for the {@link android.media.AudioTrack} buffer, in microseconds.
     */
    private static final long MIN_BUFFER_DURATION_US = 250000;
    /**
     * A maximum length for the {@link android.media.AudioTrack} buffer, in microseconds.
     */
    private static final long MAX_BUFFER_DURATION_US = 750000;

    private MediaExtractor extractor = null;
    private MediaExtractor nextExtractor = null;
    private MediaCodec codec = null;
    private AudioTrack audioTrack = null;

    private long currentExtractorPositionUs = 0;
    private long absoluteExtractedPositionUs = 0;
    private long currentCodecPositionUs = 0;
    private long absoluteDecodedPositionUs = 0;

    private int currentAsset = -1;

    private final String[] assets;

    private final Context context;

    public CustomPlayer(Context context, String[] assets) {
        this.context = context;
        this.assets = assets;
    }

    @Override
    protected Void doInBackground(Void... params) {
        decodeLoop();
        return null;
    }

    private boolean initNextExtractor() throws IOException {
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }

        if (++currentAsset < assets.length) {
            final String filename = assets[currentAsset];

            if (nextExtractor != null) {
                extractor = nextExtractor;
                nextExtractor = null;
            } else {
                extractor = initExtractor(filename);
            }

            L.d(TAG, "Extractor - asset: %s", filename);

            return true;
        } else {
            return false;
        }
    }

    private MediaExtractor initExtractor(String filename) throws IOException {
        AssetFileDescriptor afd = context.getAssets().openFd(filename);

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        afd.close();

        extractor.selectTrack(0); // <= You must select a track. You will read samples from the media from this track!
        return extractor;
    }

    private void tryPreloadNextExtractor() throws IOException {
        if (nextExtractor != null || extractor == null) {
            return;
        }

        if (currentAsset < assets.length - 1 && extractor.hasCacheReachedEndOfStream()) {
            Log.v(TAG, "tryPreloadNextExtractor - init next - current extractor info - cached duration: " + extractor.getCachedDuration() + ", cache reached end: " + extractor.hasCacheReachedEndOfStream());
            nextExtractor = initExtractor(assets[currentAsset + 1]);
        }
    }

    private long getPlaybackPositionUs() {
        if (audioTrack == null) {
            return -1;
        } else {
            return Math.round(((double) audioTrack.getPlaybackHeadPosition() / audioTrack.getSampleRate()) * 1000000);
        }
    }

    private long getAbsoluteExtractedPositionUs() {
        return absoluteExtractedPositionUs + currentExtractorPositionUs;
    }

    private long getAbsoluteDecodedPositionUs() {
        return absoluteDecodedPositionUs + currentCodecPositionUs;
    }

    private void decodeLoop() {
        Log.v(TAG, "decodeLoop");

        try {
            while (!isCancelled() && initNextExtractor()) {
                Log.d(TAG, "inited next extractor");

                ByteBuffer[] codecInputBuffers;
                ByteBuffer[] codecOutputBuffers;
                MediaCodec.BufferInfo outputBufferInfo = new MediaCodec.BufferInfo();

                Log.d(TAG, String.format("TRACKS #: %d", extractor.getTrackCount()));
                MediaFormat format = extractor.getTrackFormat(0);
                String mime = format.getString(MediaFormat.KEY_MIME);
                Log.d(TAG, String.format("MIME TYPE: %s", mime));
                int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                Log.d(TAG, String.format("SAMPLE RATE: %d", sampleRate));
                int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                Log.d(TAG, String.format("CHANNEL COUNT: %d", channelCount));
                Log.d(TAG, String.format("DURATION: %d", format.getLong(MediaFormat.KEY_DURATION)));


                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, null /* surface */, null /* crypto */, 0 /* flags */);
                codec.start();
                codecInputBuffers = codec.getInputBuffers();
                codecOutputBuffers = codec.getOutputBuffers();

                int channelConfig;
                switch (channelCount) {
                    case 1:
                        channelConfig = AudioFormat.CHANNEL_OUT_MONO;
                        break;
                    case 2:
                        channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                        break;
                    case 6:
                        channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                        break;
                    case 8:
                        channelConfig = AudioFormat.CHANNEL_OUT_7POINT1;
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported channel count: " + channelCount);
                }

                if (audioTrack == null) {
                    int encoding = AudioFormat.ENCODING_PCM_16BIT;

                    int frameSize = 2 * channelCount;
                    int minBufferSize = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding);
                    int multipliedBufferSize = minBufferSize * BUFFER_MULTIPLICATION_FACTOR;
                    int minAppBufferSize = (int) durationUsToFrames(MIN_BUFFER_DURATION_US, sampleRate) * frameSize;
                    int maxAppBufferSize = (int) Math.max(minBufferSize,
                            durationUsToFrames(MAX_BUFFER_DURATION_US, sampleRate) * frameSize);
                    int bufferSize = multipliedBufferSize < minAppBufferSize ? minAppBufferSize
                            : multipliedBufferSize > maxAppBufferSize ? maxAppBufferSize
                            : multipliedBufferSize;
                    Log.d(TAG, String.format("buffer sizes - minBufferSize: %d, minAppBufferSize: %d, maxAppBufferSize: %d, bufferSize: %d",
                            minBufferSize, minAppBufferSize, maxAppBufferSize, bufferSize));

                    audioTrack = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            sampleRate,
                            channelConfig,
                            encoding,
                            bufferSize,
                            android.media.AudioTrack.MODE_STREAM
                    );

                    audioTrack.play();
                }

                int noOutputCounter = 0;
                int noOutputCounterLimit = 50;
                boolean sawInputEOS = false;
                boolean sawOutputEOS = false;

                while (!sawOutputEOS && noOutputCounter < noOutputCounterLimit && !isCancelled()) {
                    noOutputCounter++;
                    if (!sawInputEOS) {
                        int inputBufferIndex = codec.dequeueInputBuffer(0);
                        if (inputBufferIndex >= 0) {
                            ByteBuffer dstBuf = codecInputBuffers[inputBufferIndex];

                            int sampleSize = extractor.readSampleData(dstBuf, 0);
                            long presentationTimeUs = 0;
                            if (sampleSize < 0) {
                                sawInputEOS = true;
                                sampleSize = 0;
                            } else {
                                presentationTimeUs = extractor.getSampleTime();
                                currentExtractorPositionUs = presentationTimeUs;
                            }

                            codec.queueInputBuffer(inputBufferIndex,
                                    0, //offset
                                    sampleSize,
                                    presentationTimeUs,
                                    sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                            if (!sawInputEOS) {
                                extractor.advance();
                                Log.d(TAG, "queued input buffer, size " + sampleSize + "/" + presentationTimeUs + " (absoluteExtractedPositionUs: " + getAbsoluteExtractedPositionUs() + ", track pos: " + getPlaybackPositionUs() + ")");
                            } else {
                                Log.d(TAG, "saw input EOS, size " + sampleSize + "/" + presentationTimeUs + " (absoluteExtractedPositionUs: " + getAbsoluteExtractedPositionUs() + ", track pos: " + getPlaybackPositionUs() + ")");
                            }
                        } else {
                            Log.d(TAG, "no input buffer dequeued (absoluteExtractedPositionUs: " + getAbsoluteExtractedPositionUs() + ", track pos: " + getPlaybackPositionUs() + ")");
                        }
                    }

                    int outputBufferIndex = codec.dequeueOutputBuffer(outputBufferInfo, 0);
                    if (outputBufferIndex >= 0) {

                        currentCodecPositionUs = outputBufferInfo.presentationTimeUs;
                        Log.d(TAG, "got frame, size " + outputBufferInfo.size + "/" + outputBufferInfo.presentationTimeUs + " (absoluteDecodedPositionUs: " + getAbsoluteDecodedPositionUs() + ", track pos: " + getPlaybackPositionUs() + ")");
                        if (outputBufferInfo.size > 0) {
                            noOutputCounter = 0;
                        }

                        ByteBuffer buf = codecOutputBuffers[outputBufferIndex];

                        final byte[] chunk = new byte[outputBufferInfo.size];
                        buf.get(chunk); // Read the buffer all at once
                        buf.clear(); // ** MUST DO!!! OTHERWISE THE NEXT TIME YOU GET THIS SAME BUFFER BAD THINGS WILL HAPPEN

                        if (chunk.length > 0) {
                            audioTrack.write(chunk, 0, chunk.length);
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false /* render */);

                        if ((outputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "saw output EOS.");
                            sawOutputEOS = true;
                        } else {
                            Log.d(TAG, "released output buffer, next round...");
                        }
                    } else {
                        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            // no needed to handle if API level >= 21 and using getOutputBuffer(int)
                            codecOutputBuffers = codec.getOutputBuffers();
                            Log.d(TAG, "output buffers have changed.");
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // Subsequent data will conform to new format.
                            // can ignore if API level >= 21 and using getOutputFormat(outputBufferIndex)
                            final MediaFormat oformat = codec.getOutputFormat();
                            Log.d(TAG, "Output format has changed to " + oformat);
                            audioTrack.setPlaybackRate(oformat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                        } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            Log.d(TAG, "output buffer: try again later");
                        } else {
                            Log.d(TAG, "unknown output info " + outputBufferIndex);
                        }
                    }

                    tryPreloadNextExtractor();
                }

                codec.stop();
                codec.release();
                codec = null;

                absoluteExtractedPositionUs += currentExtractorPositionUs;
                currentExtractorPositionUs = 0;
                absoluteDecodedPositionUs += currentCodecPositionUs;
                currentCodecPositionUs = 0;
            }

        } catch (IOException e) {
            Log.e(TAG, "playMedia - exception", e);
        } finally {
            releaseResources();
        }
    }

    private void releaseResources() {
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
        if (nextExtractor != null) {
            nextExtractor.release();
            nextExtractor = null;
        }
        if (codec != null) {
            codec.stop();
            codec.release();
            codec = null;
        }

        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
    }

    private static long durationUsToFrames(long durationUs, int sampleRate) {
      return (durationUs * sampleRate) / 1000000L;
    }
}
