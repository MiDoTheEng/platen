package com.platen.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Typewriter sounds. They are synthesised when the app starts (no audio files are shipped):
 * three key strikes, a space-bar thump, a soft backspace click, the carriage return and the bell.
 */
final class Sounds {

    private static final int SR = 44100;

    private final SoundPool pool;
    private final int[] keys = new int[3];
    private final int space, back, ret, bell;
    private final Random rnd = new Random();

    Sounds(Context c) {
        pool = new SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        File dir = new File(c.getCacheDir(), "snd");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        for (int i = 0; i < keys.length; i++) keys[i] = load(dir, "key" + i, synthKey(i));
        space = load(dir, "space", synthSpace());
        back = load(dir, "back", synthBack());
        ret = load(dir, "return", synthReturn());
        bell = load(dir, "bell", synthBell());
    }

    void key() {
        play(keys[rnd.nextInt(keys.length)], 0.85f, 0.94f + rnd.nextFloat() * 0.12f);
    }

    void space() {
        play(space, 0.8f, 0.96f + rnd.nextFloat() * 0.08f);
    }

    void back() {
        play(back, 0.7f, 1f);
    }

    void carriageReturn() {
        play(ret, 0.75f, 1f);
    }

    void bell() {
        play(bell, 0.55f, 1f);
    }

    void release() {
        pool.release();
    }

    private void play(int id, float vol, float rate) {
        if (id != 0) pool.play(id, vol, vol, 1, 0, rate);
    }

    private int load(File dir, String name, float[] pcm) {
        try {
            File f = new File(dir, name + ".wav");
            writeWav(f, pcm);
            return pool.load(f.getPath(), 1);
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- synthesis

    private static float[] synthKey(int v) {
        int n = (int) (SR * 0.07f);
        float[] o = new float[n];
        Random r = new Random(11 + v * 7L);
        float lp = 0;
        float ping = 1700 + v * 330;
        float body = 140 + v * 18;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            float noise = r.nextFloat() * 2 - 1;
            lp += (noise - lp) * 0.38f;
            float s = lp * exp(t, 0.0045f) * 0.95f
                    + sin(ping, t) * exp(t, 0.0022f) * 0.45f
                    + sin(body, t) * exp(t, 0.016f) * 0.55f;
            float t2 = t - 0.030f; // the type bar falling back
            if (t2 > 0) s += (r.nextFloat() * 2 - 1) * exp(t2, 0.0028f) * 0.28f;
            o[i] = s * 0.85f;
        }
        return o;
    }

    private static float[] synthSpace() {
        int n = (int) (SR * 0.09f);
        float[] o = new float[n];
        Random r = new Random(5);
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            lp += ((r.nextFloat() * 2 - 1) - lp) * 0.14f;
            o[i] = (lp * exp(t, 0.012f) * 1.4f + sin(96, t) * exp(t, 0.03f) * 0.6f) * 0.8f;
        }
        return o;
    }

    private static float[] synthBack() {
        int n = (int) (SR * 0.045f);
        float[] o = new float[n];
        Random r = new Random(3);
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            lp += ((r.nextFloat() * 2 - 1) - lp) * 0.25f;
            o[i] = (lp * exp(t, 0.004f) + sin(220, t) * exp(t, 0.01f) * 0.35f) * 0.6f;
        }
        return o;
    }

    private static float[] synthReturn() {
        float dur = 0.42f;
        int n = (int) (SR * dur);
        float[] o = new float[n];
        Random r = new Random(9);
        float lp = 0;
        // ratchet clicks that speed up as the carriage slides home
        float next = 0.0f, gap = 0.030f;
        float lastClick = -1;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            if (t >= next && t < 0.34f) {
                lastClick = t;
                next = t + gap;
                gap = Math.max(0.011f, gap * 0.9f);
            }
            lp += ((r.nextFloat() * 2 - 1) - lp) * 0.2f;
            float s = lp * 0.10f * (t < 0.34f ? 1 : 0);
            if (lastClick >= 0) {
                float dt = t - lastClick;
                s += (r.nextFloat() * 2 - 1) * exp(dt, 0.0016f) * 0.5f + sin(2400, dt) * exp(dt, 0.001f) * 0.2f;
            }
            float tt = t - 0.345f; // the carriage hitting the stop
            if (tt > 0) s += sin(110, tt) * exp(tt, 0.03f) * 0.7f + (r.nextFloat() * 2 - 1) * exp(tt, 0.006f) * 0.6f;
            o[i] = s * 0.8f;
        }
        return o;
    }

    private static float[] synthBell() {
        int n = (int) (SR * 1.1f);
        float[] o = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) SR;
            float a = Math.min(1f, t / 0.002f);
            float s = sin(2093, t) + sin(5230, t) * 0.45f * exp(t, 0.15f) + sin(3140, t) * 0.25f;
            o[i] = s * exp(t, 0.35f) * a * 0.4f;
        }
        return o;
    }

    private static float sin(float f, float t) {
        return (float) Math.sin(2 * Math.PI * f * t);
    }

    private static float exp(float t, float tau) {
        return (float) Math.exp(-t / tau);
    }

    private static void writeWav(File f, float[] pcm) throws Exception {
        int dataLen = pcm.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLen).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(SR).putInt(SR * 2).putShort((short) 2).putShort((short) 16);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLen);
        for (float s : pcm) {
            float c = Math.max(-1f, Math.min(1f, s));
            b.putShort((short) (c * 32767));
        }
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(b.array());
        }
    }
}
