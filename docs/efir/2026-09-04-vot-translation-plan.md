# Закадровый перевод (Yandex VOT) — план реализации

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ СУБ-СКИЛЛ: `superpowers:subagent-driven-development` (рекомендуется)
> или `superpowers:executing-plans`. Шаги помечены чекбоксами `- [ ]`.

**Цель:** кнопка «Перевод» в плеере «Эфира 2»: русская закадровая озвучка Яндекса поверх
иностранного ролика, подхватывается на лету, без перезапуска видео.

**Архитектура:** сетевой слой (`VotClient` + ручной protobuf) и логика состояния (`TranslationSession`)
не знают ни про Android, ни про плеер и покрываются обычными JVM-тестами. Поверх них — тонкий
`TranslationController` в списке контроллеров `PlaybackPresenter`, который зеркалит события плеера
на второй `SimpleExoPlayer` со ссылкой на mp3 и приглушает оригинал.

**Стек:** Java 8, ExoPlayer 2.10.6 (вендорится исходниками), OkHttp, RxJava2, JUnit4, Gradle 7.5 / AGP 7.4.2.

**Спека:** `docs/efir/2026-09-04-vot-translation-design.md` — читать вместе с планом.

## Глобальные ограничения

- Хост API: `api.browser.yandex.ru`; пути `/video-translation/translate`, `/video-translation/fail-audio-js`.
- HMAC-ключ: `bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf`; `COMPONENT_VERSION = 26.4.1.1026`.
- Яндексу отправляется **только** форма ссылки `https://youtu.be/<videoId>`.
- ⛔ Поле `bypassCache` не выставлять никогда (после такого запроса сервис перестаёт отвечать IP).
- Иконки кнопок плеера — **PNG 192×192 RGBA** в `common/src/main/res/drawable-nodpi/`
  (`TwoStateAction` кастует ресурс к `BitmapDrawable`, векторный drawable уронит плеер).
- Диф в апстримных файлах держать построчно минимальным: правим значения и списки, не вёрстку.
- Тесты: `./gradlew :common:testStstableDebugUnitTest`.
- Сборка APK: `./gradlew :smarttubetv:assembleStstableRelease -x lintVitalStstableRelease`,
  далее ручная подпись `<ваш keystore>.jks` (см. спеку проекта в памяти).
- Живая проверка — **только на X5M `X5M (тестовая приставка в локальной сети)`**.

---

### Задача 1: Подпись запросов (`VotCrypto`)

**Файлы:**
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotCrypto.java`
- Тест: `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/VotCryptoTest.java`

**Интерфейсы:**
- Использует: ничего.
- Отдаёт: `VotCrypto.sign(byte[] body)` → `String` (hex); `VotCrypto.signToken(String uuid, String path)` → `String`;
  `VotCrypto.randomUuid()` → `String` (32 hex-символа в верхнем регистре).

- [ ] **Шаг 1: написать падающий тест**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VotCryptoTest {
    @Test
    public void signMatchesReferenceVector() {
        byte[] body = VotHex.decode(
            "1a1c68747470733a2f2f796f7574752e62652f64517734773957675863512801310000000000a06a4038014202656e720272757801800102");
        assertEquals("824fba5fa60152b6d41c83aef1825deee0986c9317c852a0afafc4446b1feb54", VotCrypto.sign(body));
    }

    @Test
    public void signTokenMatchesReferenceVector() {
        assertEquals("b22606e3cf71ee0d8949a2202419efaace870a3d2e4ed27c8f77ba5296994d4b",
            VotCrypto.signToken("0123456789ABCDEF0123456789ABCDEF", "/video-translation/translate"));
    }

    @Test
    public void randomUuidHas32HexChars() {
        assertEquals(32, VotCrypto.randomUuid().length());
        assertEquals(true, VotCrypto.randomUuid().matches("[0-9A-F]{32}"));
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*VotCryptoTest*"`
Ожидается: FAIL, `cannot find symbol: class VotCrypto`.

- [ ] **Шаг 3: реализовать `VotHex` и `VotCrypto`**

Создать `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotHex.java`:

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

public final class VotHex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private VotHex() {}

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(DIGITS[(b >> 4) & 0xF]).append(DIGITS[b & 0xF]);
        }
        return sb.toString();
    }

    public static byte[] decode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
```

Создать `VotCrypto.java`:

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import java.nio.charset.Charset;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VotCrypto {
    public static final String COMPONENT_VERSION = "26.4.1.1026";
    private static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private VotCrypto() {}

    public static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(UTF8), "HmacSHA256"));
            return VotHex.encode(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public static String signToken(String sessionUuid, String path) {
        return sign((sessionUuid + ":" + path + ":" + COMPONENT_VERSION).getBytes(UTF8));
    }

    public static String randomUuid() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(HEX_UPPER[RANDOM.nextInt(16)]);
        }
        return sb.toString();
    }
}
```

- [ ] **Шаг 4: убедиться, что тест проходит**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*VotCryptoTest*"` — ожидается PASS.

- [ ] **Шаг 5: коммит**

```bash
git add common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/ common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/
git commit -m "VOT: подпись запросов по эталонным векторам"
```

---

### Задача 2: Сборка тела запроса (`VotProto.encodeTranslateRequest`)

**Файлы:**
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotProto.java`
- Тест: `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/VotProtoEncodeTest.java`

**Интерфейсы:**
- Использует: `VotHex` из задачи 1.
- Отдаёт: `VotProto.encodeTranslateRequest(String youtuBeUrl, double durationSec, String srcLang, String dstLang)` → `byte[]`.

Номера полей (из `yandex.proto`): `url=3`, `firstRequest=5`, `duration=6`, `unknown0=7 (1)`,
`language=8`, `forceSourceLang=9`, `unknown1=10 (0)`, `wasStream=13`, `responseLanguage=14`,
`unknown2=15 (1)`, `unknown3=16 (2)`, `bypassCache=17`, `useLivelyVoice=18`, `videoTitle=19`.
Поля со значением по умолчанию (proto3) в тело **не пишутся**.

- [ ] **Шаг 1: написать падающий тест**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VotProtoEncodeTest {
    @Test
    public void encodesReferenceRequestByteForByte() {
        byte[] body = VotProto.encodeTranslateRequest("https://youtu.be/dQw4w9WgXcQ", 213d, "en", "ru");
        assertEquals(
            "1a1c68747470733a2f2f796f7574752e62652f64517734773957675863512801310000000000a06a4038014202656e720272757801800102",
            VotHex.encode(body));
        assertEquals(56, body.length);
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*VotProtoEncodeTest*"` — FAIL.

- [ ] **Шаг 3: реализовать кодировщик**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

public final class VotProto {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private VotProto() {}

    public static byte[] encodeTranslateRequest(String url, double durationSec, String srcLang, String dstLang) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, 3, url);          // url
        writeBool(out, 5, true);           // firstRequest
        writeDouble(out, 6, durationSec);  // duration
        writeVarintField(out, 7, 1);       // unknown0
        writeString(out, 8, srcLang);      // language
        // forceSourceLang(9) = false, unknown1(10) = 0, wasStream(13) = false -> пропускаем
        writeString(out, 14, dstLang);     // responseLanguage
        writeVarintField(out, 15, 1);      // unknown2
        writeVarintField(out, 16, 2);      // unknown3
        // bypassCache(17), useLivelyVoice(18), videoTitle(19) -> пропускаем
        return out.toByteArray();
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        writeVarint(out, (field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeTag(out, field, 0);
        writeVarint(out, value);
    }

    private static void writeBool(ByteArrayOutputStream out, int field, boolean value) {
        writeVarintField(out, field, value ? 1 : 0);
    }

    private static void writeString(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(UTF8);
        writeTag(out, field, 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeDouble(ByteArrayOutputStream out, int field, double value) {
        writeTag(out, field, 1);
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) {
            out.write((int) ((bits >>> (i * 8)) & 0xFF));
        }
    }
}
```

- [ ] **Шаг 4: убедиться, что тест проходит**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*VotProtoEncodeTest*"` — PASS.
Байты обязаны совпасть **точно**: вектор снят с рабочей реализации.

- [ ] **Шаг 5: коммит**

```bash
git add common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotProto.java common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/VotProtoEncodeTest.java
git commit -m "VOT: сборка тела запроса на перевод"
```

---

### Задача 3: Разбор ответа (`VotProto.decodeTranslateResponse`)

**Файлы:**
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotProto.java`
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotResult.java`
- Тест: `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/VotProtoDecodeTest.java`

**Интерфейсы:**
- Отдаёт: `VotProto.decodeTranslateResponse(byte[] body)` → `VotResult`;
  поля `VotResult`: `int status`, `String audioUrl`, `int remainingSec`, `String translationId`,
  `String detectedLanguage`, `String message`; константы `VotResult.STATUS_FAILED = 0`,
  `STATUS_FINISHED = 1`, `STATUS_WAITING = 2`, `STATUS_LONG_WAITING = 3`, `STATUS_PART_CONTENT = 5`,
  `STATUS_AUDIO_REQUESTED = 6`, `STATUS_SESSION_REQUIRED = 7`; метод `isReady()` — `status` равен
  `STATUS_FINISHED` или `STATUS_PART_CONTENT` и `audioUrl` не пуст.

Номера полей ответа: `url=1`, `duration=2`, `status=4`, `remainingTime=5`, `unknown0=6`,
`translationId=7`, `language=8`, `message=9`. Неизвестные поля обязаны пропускаться по wire type.

- [ ] **Шаг 1: написать падающий тест**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.*;

public class VotProtoDecodeTest {
    @Test
    public void decodesFinishedResponse() {
        VotResult r = VotProto.decodeTranslateResponse(VotHex.decode(
            "0a3868747470733a2f2f767472616e732e73332d707269766174652e6d64732e79616e6465782e6e65742f7474732f70726f642f61612e6d7033110000000000a06a4020013a057469643432"));
        assertEquals(VotResult.STATUS_FINISHED, r.status);
        assertEquals("https://vtrans.s3-private.mds.yandex.net/tts/prod/aa.mp3", r.audioUrl);
        assertEquals("tid42", r.translationId);
        assertTrue(r.isReady());
    }

    @Test
    public void decodesWaitingResponse() {
        VotResult r = VotProto.decodeTranslateResponse(VotHex.decode("2002281d3a057469643432"));
        assertEquals(VotResult.STATUS_WAITING, r.status);
        assertEquals(29, r.remainingSec);
        assertNull(r.audioUrl);
        assertFalse(r.isReady());
    }

    @Test
    public void skipsUnknownFields() {
        // поле 99 (varint) перед статусом не должно ломать разбор
        VotResult r = VotProto.decodeTranslateResponse(VotHex.decode("d80607" + "2002281d"));
        assertEquals(VotResult.STATUS_WAITING, r.status);
        assertEquals(29, r.remainingSec);
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*VotProtoDecodeTest*"` — FAIL.

- [ ] **Шаг 3: реализовать `VotResult` и разбор**

`VotResult.java`:

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

public class VotResult {
    public static final int STATUS_FAILED = 0;
    public static final int STATUS_FINISHED = 1;
    public static final int STATUS_WAITING = 2;
    public static final int STATUS_LONG_WAITING = 3;
    public static final int STATUS_PART_CONTENT = 5;
    public static final int STATUS_AUDIO_REQUESTED = 6;
    public static final int STATUS_SESSION_REQUIRED = 7;

    public int status;
    public String audioUrl;
    public int remainingSec = -1;
    public String translationId;
    public String detectedLanguage;
    public String message;

    public boolean isReady() {
        return (status == STATUS_FINISHED || status == STATUS_PART_CONTENT) && audioUrl != null && !audioUrl.isEmpty();
    }
}
```

Добавить в `VotProto`:

```java
    public static VotResult decodeTranslateResponse(byte[] data) {
        VotResult result = new VotResult();
        int pos = 0;
        while (pos < data.length) {
            long[] tag = readVarint(data, pos);
            pos = (int) tag[1];
            int field = (int) (tag[0] >>> 3);
            int wireType = (int) (tag[0] & 0x7);

            if (wireType == 0) { // varint
                long[] v = readVarint(data, pos);
                pos = (int) v[1];
                if (field == 4) {
                    result.status = (int) v[0];
                } else if (field == 5) {
                    result.remainingSec = (int) v[0];
                }
            } else if (wireType == 1) { // 64-bit
                pos += 8;
            } else if (wireType == 2) { // length-delimited
                long[] len = readVarint(data, pos);
                pos = (int) len[1];
                int size = (int) len[0];
                String value = new String(data, pos, size, UTF8);
                if (field == 1) {
                    result.audioUrl = value;
                } else if (field == 7) {
                    result.translationId = value;
                } else if (field == 8) {
                    result.detectedLanguage = value;
                } else if (field == 9) {
                    result.message = value;
                }
                pos += size;
            } else if (wireType == 5) { // 32-bit
                pos += 4;
            } else {
                throw new IllegalArgumentException("Unsupported wire type: " + wireType);
            }
        }
        return result;
    }

    /** @return [значение, новая позиция] */
    private static long[] readVarint(byte[] data, int pos) {
        long value = 0;
        int shift = 0;
        while (true) {
            int b = data[pos++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return new long[] {value, pos};
    }
```

- [ ] **Шаг 4: убедиться, что тесты проходят**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*VotProto*"` — PASS (все три теста).

- [ ] **Шаг 5: коммит**

```bash
git add common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/ common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/VotProtoDecodeTest.java
git commit -m "VOT: разбор ответа сервиса перевода"
```

---

### Задача 4: Сетевой клиент (`VotClient`)

**Файлы:**
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotTransport.java`
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotClient.java`
- Тест: `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/VotClientTest.java`

**Интерфейсы:**
- Использует: `VotProto`, `VotCrypto`, `VotResult`.
- Отдаёт:
  - `interface VotTransport { byte[] post(String path, byte[] body, Map<String, String> headers) throws IOException; }`
  - `VotClient(VotTransport transport)`; `VotClient.videoUrl(String videoId)` → `String` (`https://youtu.be/<id>`);
    `VotClient.translate(String videoId, double durationSec, String srcLang, String dstLang)` → `VotResult` (бросает `IOException`).
  - Реализация транспорта поверх OkHttp: `OkHttpVotTransport implements VotTransport` в том же файле `VotTransport.java`.

Сессия (`uuid` + `secretKey`) в этой задаче создаётся упрощённо: `uuid = VotCrypto.randomUuid()`,
`secretKey = "" `, — Яндекс принимает такую пару для `video-translation` (проверено спайком).
Заголовки запроса: `Vtrans-Signature`, `Sec-Vtrans-Sk`, `Sec-Vtrans-Token`, `User-Agent` Яндекс.Браузера,
`Content-Type: application/protobuf`.

- [ ] **Шаг 1: написать падающий тест**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class VotClientTest {
    private static class FakeTransport implements VotTransport {
        String lastPath;
        byte[] lastBody;
        Map<String, String> lastHeaders;
        byte[] response = VotHex.decode("2002281d3a057469643432");

        @Override
        public byte[] post(String path, byte[] body, Map<String, String> headers) {
            lastPath = path;
            lastBody = body;
            lastHeaders = new HashMap<>(headers);
            return response;
        }
    }

    @Test
    public void buildsYoutuBeUrl() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", VotClient.videoUrl("dQw4w9WgXcQ"));
    }

    @Test
    public void sendsSignedRequestAndParsesResponse() throws Exception {
        FakeTransport transport = new FakeTransport();
        VotResult result = new VotClient(transport).translate("dQw4w9WgXcQ", 213d, "en", "ru");

        assertEquals("/video-translation/translate", transport.lastPath);
        assertEquals(
            "1a1c68747470733a2f2f796f7574752e62652f64517734773957675863512801310000000000a06a4038014202656e720272757801800102",
            VotHex.encode(transport.lastBody));
        assertEquals(VotCrypto.sign(transport.lastBody), transport.lastHeaders.get("Vtrans-Signature"));
        assertNotNull(transport.lastHeaders.get("Sec-Vtrans-Token"));
        assertEquals(VotResult.STATUS_WAITING, result.status);
        assertEquals(29, result.remainingSec);
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*VotClientTest*"` — FAIL.

- [ ] **Шаг 3: реализовать транспорт и клиент**

`VotTransport.java`:

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public interface VotTransport {
    byte[] post(String path, byte[] body, Map<String, String> headers) throws IOException;

    class OkHttpVotTransport implements VotTransport {
        private static final MediaType PROTOBUF = MediaType.parse("application/protobuf");
        private static final String BASE_URL = "https://api.browser.yandex.ru";
        private final OkHttpClient mClient = new OkHttpClient();

        @Override
        public byte[] post(String path, byte[] body, Map<String, String> headers) throws IOException {
            Request.Builder builder = new Request.Builder()
                    .url(BASE_URL + path)
                    .post(RequestBody.create(PROTOBUF, body));

            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }

            try (Response response = mClient.newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("VOT request failed: " + response.code());
                }
                return response.body().bytes();
            }
        }
    }
}
```

`VotClient.java`:

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class VotClient {
    public static final String PATH_TRANSLATE = "/video-translation/translate";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/147.0.0.0 YaBrowser/26.4.1.1026 Yowser/2.5 Safari/537.36";

    private final VotTransport mTransport;
    private final String mSessionUuid = VotCrypto.randomUuid();

    public VotClient(VotTransport transport) {
        mTransport = transport;
    }

    public static String videoUrl(String videoId) {
        return "https://youtu.be/" + videoId;
    }

    public VotResult translate(String videoId, double durationSec, String srcLang, String dstLang) throws IOException {
        byte[] body = VotProto.encodeTranslateRequest(videoUrl(videoId), durationSec, srcLang, dstLang);

        Map<String, String> headers = new HashMap<>();
        headers.put("Vtrans-Signature", VotCrypto.sign(body));
        headers.put("Sec-Vtrans-Sk", "");
        headers.put("Sec-Vtrans-Token", VotCrypto.signToken(mSessionUuid, PATH_TRANSLATE) + ":" +
                mSessionUuid + ":" + PATH_TRANSLATE + ":" + VotCrypto.COMPONENT_VERSION);
        headers.put("User-Agent", USER_AGENT);

        return VotProto.decodeTranslateResponse(mTransport.post(PATH_TRANSLATE, body, headers));
    }
}
```

- [ ] **Шаг 4: убедиться, что тесты проходят**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*Vot*"` — PASS (все тесты модуля).

- [ ] **Шаг 5: живая проверка клиента (одноразовый прогон)**

Собрать `assembleStstableRelease`, поставить на X5M `.64`, открыть англоязычный ролик, нажать кнопку —
на этом шаге кнопки ещё нет, поэтому проверка откладывается до задачи 8. Здесь достаточно
`./gradlew :common:compileStstableDebugJavaWithJavac` — код компилируется.

- [ ] **Шаг 6: коммит**

```bash
git add common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/ common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/VotClientTest.java
git commit -m "VOT: сетевой клиент поверх OkHttp с внедряемым транспортом"
```

---

### Задача 5: Дозаявка нового ролика и уточнение языка

**Файлы:**
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotTransport.java`
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotProto.java`
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotClient.java`
- Тест: `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/VotClientTest.java` (дополняется)

**Интерфейсы:**
- Использует: `VotProto`, `VotCrypto`, `VotResult` (задачи 1–3).
- Отдаёт:
  - `VotTransport.putJson(String path, String json)` → `void` (бросает `IOException`);
  - `VotProto.encodeAudioRequest(String translationId, String youtuBeUrl, String fileId)` → `byte[]`;
  - `VotClient.PATH_FAIL_AUDIO`, `VotClient.PATH_AUDIO`, `VotClient.FILE_ID_FAILED_AUDIO` — константы;
  - поведение `VotClient.translate(...)` расширяется: статус 6 и подсказанный язык обрабатываются внутри.

Ролик, которого Яндекс раньше не видел, отвечает статусом 6 (`AUDIO_REQUESTED`). Пока клиент не
«дозаявит» его — отчётом о неудачном получении аудио (`fail-audio-js`, обычный JSON `PUT`) и пустой
посылкой в `/video-translation/audio` — перевод не начнётся. Без этого шага такие ролики висят в
ожидании вечно; в спайке он отработал незаметно и именно поэтому непереведённый ролик всё же
перевёлся. Дозаявка делается **один раз** за попытку: повторный статус 6 после неё считается отказом.

Второе: при `forceSourceLang=false` Яндекс сам определяет язык и при расхождении возвращает его
в поле `language`. Тогда делаем **один** повторный запрос с подсказанным языком.

- [ ] **Шаг 1: написать падающий тест**

Добавить в `VotClientTest` (класс `FakeTransport` дополняется очередью ответов):

```java
    private static class ScriptedTransport implements VotTransport {
        final java.util.List<byte[]> responses = new java.util.ArrayList<>();
        final java.util.List<String> calls = new java.util.ArrayList<>();
        final java.util.List<byte[]> bodies = new java.util.ArrayList<>();

        @Override
        public byte[] post(String path, byte[] body, Map<String, String> headers) {
            calls.add("POST " + path);
            bodies.add(body);
            return responses.remove(0);
        }

        @Override
        public void putJson(String path, String json) {
            calls.add("PUT " + path + " " + json);
        }
    }

    @Test
    public void claimsNewVideoOnAudioRequestedStatus() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        // 1-й ответ: status 6 + translationId, 2-й: пустой ответ на /audio, 3-й: ожидание
        transport.responses.add(VotHex.decode("2006" + "3a057469643432"));
        transport.responses.add(new byte[0]);
        transport.responses.add(VotHex.decode("2002281d3a057469643432"));

        VotResult result = new VotClient(transport).translate("dQw4w9WgXcQ", 213d, "en", "ru");

        assertEquals("PUT /video-translation/fail-audio-js {\"video_url\":\"https://youtu.be/dQw4w9WgXcQ\"}",
                transport.calls.get(1));
        assertEquals("POST /video-translation/audio", transport.calls.get(2));
        assertEquals(
            "0a057469643432121c68747470733a2f2f796f7574752e62652f645177347739576758635132320a307765625f6170695f6765745f616c6c5f67656e65726174696e675f75726c735f646174615f66726f6d5f696672616d65",
            VotHex.encode(transport.bodies.get(1)));
        assertEquals(VotResult.STATUS_WAITING, result.status);
    }

    @Test
    public void retriesOnceWithDetectedLanguage() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        // 1-й ответ: отказ с подсказкой языка "de", 2-й: ожидание
        transport.responses.add(VotHex.decode("2000" + "42026465"));
        transport.responses.add(VotHex.decode("2002281d"));

        VotResult result = new VotClient(transport).translate("dQw4w9WgXcQ", 213d, "en", "ru");

        // второй запрос ушёл уже с языком de
        assertEquals("4202" + "6465", VotHex.encode(transport.bodies.get(1)).substring(
                VotHex.encode(transport.bodies.get(1)).indexOf("4202"),
                VotHex.encode(transport.bodies.get(1)).indexOf("4202") + 8));
        assertEquals(VotResult.STATUS_WAITING, result.status);
    }
```

- [ ] **Шаг 2: убедиться, что тесты падают**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*VotClientTest*"`
Ожидается: FAIL — `putJson` не объявлен в `VotTransport`.

- [ ] **Шаг 3: добавить `putJson` в транспорт**

В `VotTransport` добавить метод интерфейса и его реализацию в `OkHttpVotTransport`:

```java
    void putJson(String path, String json) throws IOException;
```

```java
        private static final MediaType JSON = MediaType.parse("application/json");

        @Override
        public void putJson(String path, String json) throws IOException {
            Request request = new Request.Builder()
                    .url(BASE_URL + path)
                    .put(RequestBody.create(JSON, json))
                    .build();

            try (Response response = mClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("VOT fail-audio request failed: " + response.code());
                }
            }
        }
```

- [ ] **Шаг 4: добавить сборку запроса `/audio` в `VotProto`**

Поля: `VideoTranslationAudioRequest.translationId = 1`, `url = 2`, `audioInfo = 6`;
внутри `AudioBufferObject`: `fileId = 1`, `audioFile = 2` (пустой — не пишем).

```java
    public static byte[] encodeAudioRequest(String translationId, String url, String fileId) {
        ByteArrayOutputStream audioInfo = new ByteArrayOutputStream();
        writeString(audioInfo, 1, fileId); // AudioBufferObject.fileId

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, 1, translationId);
        writeString(out, 2, url);

        byte[] audioInfoBytes = audioInfo.toByteArray();
        writeTag(out, 6, 2);
        writeVarint(out, audioInfoBytes.length);
        out.write(audioInfoBytes, 0, audioInfoBytes.length);

        return out.toByteArray();
    }
```

- [ ] **Шаг 5: реализовать обе ветки в `VotClient`**

Вынести текущее тело `translate` в приватный `requestTranslation`, а публичный метод сделать таким:

```java
    public static final String PATH_FAIL_AUDIO = "/video-translation/fail-audio-js";
    public static final String PATH_AUDIO = "/video-translation/audio";
    public static final String FILE_ID_FAILED_AUDIO = "web_api_get_all_generating_urls_data_from_iframe";

    public VotResult translate(String videoId, double durationSec, String srcLang, String dstLang) throws IOException {
        VotResult result = requestTranslation(videoId, durationSec, srcLang, dstLang);

        if (result.status == VotResult.STATUS_AUDIO_REQUESTED) {
            claimVideo(videoId, result.translationId);
            result = requestTranslation(videoId, durationSec, srcLang, dstLang);
        }

        if (result.status == VotResult.STATUS_FAILED && result.detectedLanguage != null
                && !result.detectedLanguage.isEmpty() && !result.detectedLanguage.equals(srcLang)) {
            // Яндекс сам распознал язык и вернул его — повторяем ровно один раз
            result = requestTranslation(videoId, durationSec, result.detectedLanguage, dstLang);
        }

        return result;
    }

    private void claimVideo(String videoId, String translationId) throws IOException {
        String url = videoUrl(videoId);
        mTransport.putJson(PATH_FAIL_AUDIO, "{\"video_url\":\"" + url + "\"}");

        byte[] body = VotProto.encodeAudioRequest(translationId, url, FILE_ID_FAILED_AUDIO);
        mTransport.post(PATH_AUDIO, body, headers(body, PATH_AUDIO));
    }
```

Заголовки вынести из `requestTranslation` в общий приватный метод:

```java
    private Map<String, String> headers(byte[] body, String path) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Vtrans-Signature", VotCrypto.sign(body));
        headers.put("Sec-Vtrans-Sk", "");
        headers.put("Sec-Vtrans-Token", VotCrypto.signToken(mSessionUuid, path) + ":" +
                mSessionUuid + ":" + path + ":" + VotCrypto.COMPONENT_VERSION);
        headers.put("User-Agent", USER_AGENT);
        return headers;
    }
```

- [ ] **Шаг 6: убедиться, что тесты проходят**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*Vot*"` — PASS (все тесты, включая старые).

- [ ] **Шаг 7: коммит**

```bash
git add common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/ common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/VotClientTest.java
git commit -m "VOT: дозаявка нового ролика и повтор с распознанным языком"
```

---

### Задача 6: Логика ожидания (`TranslationSession`)

**Файлы:**
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/TranslationSession.java`
- Тест: `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/TranslationSessionTest.java`

**Интерфейсы:**
- Использует: `VotResult` (задача 3).
- Отдаёт: `TranslationSession(long maxWaitMs)`; методы
  `onResult(VotResult result, long nowMs)` → `Decision`;
  `enum Decision { WAIT, READY, GIVE_UP }`;
  `long nextPollDelayMs()`; `String audioUrl()`; `int lastRemainingSec()`; `void start(long nowMs)`.

Правила: `STATUS_WAITING`/`STATUS_LONG_WAITING`/`STATUS_AUDIO_REQUESTED` → `WAIT` с задержкой
`max(remainingSec, 5) * 1000`; готовый результат → `READY`; `STATUS_FAILED` → `GIVE_UP`;
превышение `maxWaitMs` от `start()` → `GIVE_UP`.

- [ ] **Шаг 1: написать падающий тест**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationSessionTest {
    private VotResult waiting(int remainingSec) {
        VotResult r = new VotResult();
        r.status = VotResult.STATUS_WAITING;
        r.remainingSec = remainingSec;
        return r;
    }

    private VotResult finished() {
        VotResult r = new VotResult();
        r.status = VotResult.STATUS_FINISHED;
        r.audioUrl = "https://vtrans.example/aa.mp3";
        return r;
    }

    @Test
    public void waitsUsingServerCountdown() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        assertEquals(TranslationSession.Decision.WAIT, session.onResult(waiting(29), 1_000));
        assertEquals(29_000, session.nextPollDelayMs());
        assertEquals(29, session.lastRemainingSec());
    }

    @Test
    public void clampsTooShortCountdownToFiveSeconds() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        session.onResult(waiting(1), 1_000);
        assertEquals(5_000, session.nextPollDelayMs());
    }

    @Test
    public void reportsReadyWithAudioUrl() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        assertEquals(TranslationSession.Decision.READY, session.onResult(finished(), 90_000));
        assertEquals("https://vtrans.example/aa.mp3", session.audioUrl());
    }

    @Test
    public void givesUpAfterMaxWait() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        assertEquals(TranslationSession.Decision.GIVE_UP, session.onResult(waiting(5), 600_001));
    }

    @Test
    public void givesUpOnFailedStatus() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        VotResult failed = new VotResult();
        failed.status = VotResult.STATUS_FAILED;
        assertEquals(TranslationSession.Decision.GIVE_UP, session.onResult(failed, 1_000));
    }
}
```

- [ ] **Шаг 2: убедиться, что тесты падают**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*TranslationSessionTest*"` — FAIL.

- [ ] **Шаг 3: реализовать**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

public class TranslationSession {
    public enum Decision { WAIT, READY, GIVE_UP }

    private static final long MIN_POLL_DELAY_MS = 5_000;

    private final long mMaxWaitMs;
    private long mStartMs;
    private long mNextPollDelayMs = MIN_POLL_DELAY_MS;
    private int mLastRemainingSec = -1;
    private String mAudioUrl;

    public TranslationSession(long maxWaitMs) {
        mMaxWaitMs = maxWaitMs;
    }

    public void start(long nowMs) {
        mStartMs = nowMs;
        mAudioUrl = null;
        mNextPollDelayMs = MIN_POLL_DELAY_MS;
        mLastRemainingSec = -1;
    }

    public Decision onResult(VotResult result, long nowMs) {
        if (result.isReady()) {
            mAudioUrl = result.audioUrl;
            return Decision.READY;
        }

        if (result.status == VotResult.STATUS_FAILED) {
            return Decision.GIVE_UP;
        }

        if (nowMs - mStartMs > mMaxWaitMs) {
            return Decision.GIVE_UP;
        }

        mLastRemainingSec = result.remainingSec;
        mNextPollDelayMs = Math.max(result.remainingSec * 1000L, MIN_POLL_DELAY_MS);

        return Decision.WAIT;
    }

    public long nextPollDelayMs() {
        return mNextPollDelayMs;
    }

    public int lastRemainingSec() {
        return mLastRemainingSec;
    }

    public String audioUrl() {
        return mAudioUrl;
    }
}
```

- [ ] **Шаг 4: убедиться, что тесты проходят**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*TranslationSessionTest*"` — PASS.

- [ ] **Шаг 5: коммит**

```bash
git add common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/TranslationSession.java common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/TranslationSessionTest.java
git commit -m "VOT: конечный автомат ожидания перевода"
```

---

### Задача 7: Второй плеер и синхронизация (`TranslationAudioPlayer`)

**Файлы:**
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/SyncDecider.java`
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/TranslationAudioPlayer.java`
- Тест: `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/SyncDeciderTest.java`

**Интерфейсы:**
- Отдаёт: `SyncDecider.shouldResync(long mainPosMs, long votPosMs, long thresholdMs)` → `boolean`;
  `TranslationAudioPlayer(Context context)` с методами `play(String audioUrl, long positionMs)`,
  `pause()`, `resume()`, `seekTo(long positionMs)`, `setSpeed(float speed)`, `long positionMs()`,
  `boolean isPlaying()`, `release()`, `setErrorListener(Runnable onError)`.

- [ ] **Шаг 1: написать падающий тест на решающее правило**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyncDeciderTest {
    @Test
    public void ignoresSmallDrift() {
        assertFalse(SyncDecider.shouldResync(10_000, 10_250, 300));
        assertFalse(SyncDecider.shouldResync(10_000, 9_800, 300));
    }

    @Test
    public void resyncsOnLargeDriftBothDirections() {
        assertTrue(SyncDecider.shouldResync(10_000, 10_400, 300));
        assertTrue(SyncDecider.shouldResync(10_000, 9_500, 300));
    }
}
```

- [ ] **Шаг 2: убедиться, что тест падает**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*SyncDeciderTest*"` — FAIL.

- [ ] **Шаг 3: реализовать правило**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

public final class SyncDecider {
    private SyncDecider() {}

    public static boolean shouldResync(long mainPosMs, long votPosMs, long thresholdMs) {
        return Math.abs(mainPosMs - votPosMs) > thresholdMs;
    }
}
```

- [ ] **Шаг 4: убедиться, что тест проходит**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*SyncDeciderTest*"` — PASS.

- [ ] **Шаг 5: реализовать плеер дорожки**

```java
package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import android.net.Uri;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;

public class TranslationAudioPlayer {
    private final Context mContext;
    private SimpleExoPlayer mPlayer;
    private Runnable mErrorListener;

    public TranslationAudioPlayer(Context context) {
        mContext = context;
    }

    public void setErrorListener(Runnable listener) {
        mErrorListener = listener;
    }

    public void play(String audioUrl, long positionMs) {
        release();

        mPlayer = ExoPlayerFactory.newSimpleInstance(mContext);
        mPlayer.addListener(new Player.EventListener() {
            @Override
            public void onPlayerError(ExoPlaybackException error) {
                if (mErrorListener != null) {
                    mErrorListener.run();
                }
            }
        });

        DefaultHttpDataSourceFactory dataSourceFactory =
                new DefaultHttpDataSourceFactory("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        mPlayer.prepare(new ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(Uri.parse(audioUrl)));
        mPlayer.seekTo(positionMs);
        mPlayer.setPlayWhenReady(true);
    }

    public void pause() {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(false);
        }
    }

    public void resume() {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(true);
        }
    }

    public void seekTo(long positionMs) {
        if (mPlayer != null) {
            mPlayer.seekTo(positionMs);
        }
    }

    public void setSpeed(float speed) {
        if (mPlayer != null) {
            mPlayer.setPlaybackParameters(new PlaybackParameters(speed));
        }
    }

    public long positionMs() {
        return mPlayer != null ? mPlayer.getCurrentPosition() : 0;
    }

    public boolean isPlaying() {
        return mPlayer != null && mPlayer.getPlayWhenReady();
    }

    public void release() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }
}
```

- [ ] **Шаг 6: проверить компиляцию**

Запустить: `./gradlew :common:compileStstableDebugJavaWithJavac` — BUILD SUCCESSFUL.

- [ ] **Шаг 7: коммит**

```bash
git add common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/ common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/SyncDeciderTest.java
git commit -m "VOT: плеер переводной дорожки и правило досинхронизации"
```

---

### Задача 8: Кнопка в плеере и контроллер

**Файлы:**
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/TranslationController.java`
- Создать: `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/actions/TranslationAction.java`
- Создать: `common/src/main/res/drawable-nodpi/action_translation.png`
- Изменить: `common/src/main/res/values/ids.xml` (добавить `action_translation`)
- Изменить: `common/src/main/res/values/strings.xml` и `common/src/main/res/values-ru/strings.xml`
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/PlayerTweaksData.java:45,63-64`
- Изменить: `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/other/VideoPlayerGlue.java:136,191`
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/PlaybackPresenter.java:62`
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/settings/PlayerSettingsPresenter.java:222`

**Интерфейсы:**
- Использует: `VotClient`, `VotTransport.OkHttpVotTransport`, `TranslationSession`,
  `TranslationAudioPlayer`, `SyncDecider`, `VotResult`.
- Отдаёт: `TranslationController extends BasePlayerController` — контроллер без публичного API,
  подключается строкой в `PlaybackPresenter`.

- [ ] **Шаг 1: нарисовать иконку**

Иконки кнопок — PNG 192×192 RGBA. Глиф «文A» белым по прозрачному:

```bash
export PATH="$HOME/.local/bin:$PATH"
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i "color=c=black@0.0:s=192x192,format=rgba" \
  -vf "drawtext=fontfile=/System/Library/Fonts/Supplemental/Arial Unicode.ttf:text='文A':fontcolor=white:fontsize=104:x=(w-text_w)/2:y=(h-text_h)/2-8" \
  -frames:v 1 -y common/src/main/res/drawable-nodpi/action_translation.png
file common/src/main/res/drawable-nodpi/action_translation.png
```

Ожидается: `PNG image data, 192 x 192, 8-bit/color RGBA`.

- [ ] **Шаг 2: добавить ресурсы**

В `common/src/main/res/values/ids.xml` рядом с `action_sound_off`:

```xml
    <item name="action_translation" type="id"/>
```

В `common/src/main/res/values/strings.xml`:

```xml
    <string name="action_translation">Translation</string>
    <string name="vot_preparing">Preparing voice-over, ~%1$s</string>
    <string name="vot_enabled">Translation on</string>
    <string name="vot_failed">Yandex could not translate this video</string>
```

В `common/src/main/res/values-ru/strings.xml`:

```xml
    <string name="action_translation">Перевод</string>
    <string name="vot_preparing">Готовим озвучку, ~%1$s</string>
    <string name="vot_enabled">Перевод включён</string>
    <string name="vot_failed">Яндекс не смог перевести этот ролик</string>
```

- [ ] **Шаг 3: добавить флаг кнопки**

В `PlayerTweaksData.java` после `PLAYER_BUTTON_VIDEO_FLIP` (строка 45):

```java
    public static final int PLAYER_BUTTON_TRANSLATION = 1 << 28;
```

и в `PLAYER_BUTTON_DEFAULT` (строки 63-64):

```java
    public static final int PLAYER_BUTTON_DEFAULT = PLAYER_BUTTON_PLAY_PAUSE | PLAYER_BUTTON_HIGH_QUALITY |
            PLAYER_BUTTON_SUBTITLES | PLAYER_BUTTON_SUBSCRIBE | PLAYER_BUTTON_TRANSLATION;
```

- [ ] **Шаг 4: создать действие**

`TranslationAction.java` — по образцу `SoundOffAction`:

```java
package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.R;

public class TranslationAction extends TwoStateAction {
    public TranslationAction(Context context) {
        super(context, R.id.action_translation, R.drawable.action_translation);

        String label = context.getString(R.string.action_translation);
        String[] labels = new String[2];
        labels[INDEX_OFF] = label;
        labels[INDEX_ON] = label;
        setLabels(labels);
    }
}
```

- [ ] **Шаг 5: подключить кнопку к панели**

В `VideoPlayerGlue.java` после `putAction(new SoundOffAction(context));` (строка 136):

```java
        putAction(new TranslationAction(context));
```

и в `onCreatePrimaryActions` рядом с блоком `PLAYER_BUTTON_SOUND_OFF` (строка 191):

```java
        if (mPlayerTweaksData.isPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_TRANSLATION)) {
            adapter.add(mActions.get(R.id.action_translation));
        }
```

Импорт: `import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.TranslationAction;`

В `PlayerSettingsPresenter.java` (строка 222, список кнопок настроек) добавить строку:

```java
                {R.string.action_translation, PlayerTweaksData.PLAYER_BUTTON_TRANSLATION},
```

- [ ] **Шаг 6: написать контроллер**

```java
package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.vot.SyncDecider;
import com.liskovsoft.smartyoutubetv2.common.vot.TranslationAudioPlayer;
import com.liskovsoft.smartyoutubetv2.common.vot.TranslationSession;
import com.liskovsoft.smartyoutubetv2.common.vot.VotClient;
import com.liskovsoft.smartyoutubetv2.common.vot.VotResult;
import com.liskovsoft.smartyoutubetv2.common.vot.VotTransport;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class TranslationController extends BasePlayerController {
    private static final long MAX_WAIT_MS = 600_000;
    private static final long SYNC_INTERVAL_MS = 1_000;
    private static final long SYNC_THRESHOLD_MS = 300;
    private static final float DUCKED_VOLUME = 0.15f;

    private final TranslationSession mSession = new TranslationSession(MAX_WAIT_MS);
    private VotClient mClient;
    private TranslationAudioPlayer mAudioPlayer;
    private Disposable mPollAction;
    private Disposable mSyncAction;
    private String mVideoId;
    private boolean mEnabled;

    @Override
    public void onVideoLoaded(Video item) {
        // новый ролик — прошлый перевод не переносим
        stopTranslation();
        mVideoId = item != null ? item.videoId : null;
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId != R.id.action_translation) {
            return;
        }

        if (mEnabled) {
            stopTranslation();
            getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_OFF);
        } else {
            startTranslation();
            getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_ON);
        }
    }

    private void startTranslation() {
        if (mVideoId == null || getPlayer() == null) {
            return;
        }

        mEnabled = true;
        mClient = new VotClient(new VotTransport.OkHttpVotTransport());
        mSession.start(System.currentTimeMillis());
        poll(0);
    }

    private void poll(long delayMs) {
        disposePoll();

        double duration = getPlayer().getDurationMs() / 1000d;
        String videoId = mVideoId;

        mPollAction = Observable.timer(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .observeOn(Schedulers.io())
                .map(ignored -> mClient.translate(videoId, duration, "en", "ru"))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onPollResult, error -> onPollError());
    }

    private void onPollResult(VotResult result) {
        if (!mEnabled) {
            return;
        }

        switch (mSession.onResult(result, System.currentTimeMillis())) {
            case READY:
                startAudio(mSession.audioUrl());
                break;
            case WAIT:
                if (mSession.lastRemainingSec() > 0) {
                    MessageHelpers.showMessage(getContext(),
                            getContext().getString(R.string.vot_preparing, formatWait(mSession.lastRemainingSec())));
                }
                poll(mSession.nextPollDelayMs());
                break;
            case GIVE_UP:
                MessageHelpers.showMessage(getContext(), R.string.vot_failed);
                stopTranslation();
                getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_OFF);
                break;
        }
    }

    private String formatWait(int remainingSec) {
        return remainingSec >= 60 ? (remainingSec / 60 + " мин") : (remainingSec + " с");
    }

    private void onPollError() {
        MessageHelpers.showMessage(getContext(), R.string.vot_failed);
        stopTranslation();
        getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_OFF);
    }

    private void startAudio(String audioUrl) {
        mAudioPlayer = new TranslationAudioPlayer(getContext());
        mAudioPlayer.play(audioUrl, getPlayer().getPositionMs());
        mAudioPlayer.setSpeed(getPlayer().getSpeed());
        getPlayer().setVolume(DUCKED_VOLUME);
        MessageHelpers.showMessage(getContext(), R.string.vot_enabled);
        startSyncTicker();
    }

    private void startSyncTicker() {
        disposeSync();

        mSyncAction = Observable.interval(SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> {
                    if (mAudioPlayer == null || getPlayer() == null) {
                        return;
                    }
                    if (SyncDecider.shouldResync(getPlayer().getPositionMs(), mAudioPlayer.positionMs(), SYNC_THRESHOLD_MS)) {
                        mAudioPlayer.seekTo(getPlayer().getPositionMs());
                    }
                });
    }

    @Override
    public void onPlay() {
        if (mAudioPlayer != null) {
            mAudioPlayer.seekTo(getPlayer().getPositionMs());
            mAudioPlayer.resume();
        }
    }

    @Override
    public void onPause() {
        if (mAudioPlayer != null) {
            mAudioPlayer.pause();
        }
    }

    @Override
    public void onSeekEnd() {
        if (mAudioPlayer != null) {
            mAudioPlayer.seekTo(getPlayer().getPositionMs());
        }
    }

    @Override
    public void onSpeedChanged(float speed) {
        if (mAudioPlayer != null) {
            mAudioPlayer.setSpeed(speed);
        }
    }

    @Override
    public void onEngineReleased() {
        stopTranslation();
    }

    @Override
    public void onFinish() {
        stopTranslation();
    }

    private void stopTranslation() {
        mEnabled = false;
        disposePoll();
        disposeSync();

        if (mAudioPlayer != null) {
            mAudioPlayer.release();
            mAudioPlayer = null;
        }

        if (getPlayer() != null) {
            getPlayer().setVolume(getPlayerData().getPlayerVolume());
        }
    }

    private void disposePoll() {
        if (mPollAction != null && !mPollAction.isDisposed()) {
            mPollAction.dispose();
        }
    }

    private void disposeSync() {
        if (mSyncAction != null && !mSyncAction.isDisposed()) {
            mSyncAction.dispose();
        }
    }
}
```

- [ ] **Шаг 7: зарегистрировать контроллер**

В `PlaybackPresenter.java` после строки 62 (`mEventListeners.add(new PlayerUIController());`):

```java
        mEventListeners.add(new TranslationController());
```

Импорт: `import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.TranslationController;`

- [ ] **Шаг 8: собрать и проверить компиляцию**

Запустить:

```bash
export JAVA_HOME=$HOME/.local/jdk17/Contents/Home; export ANDROID_HOME=$HOME/.local/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :smarttubetv:assembleStstableRelease -x lintVitalStstableRelease
```

Ожидается: BUILD SUCCESSFUL. Имена сверены с живыми интерфейсами: `PlayerEngine` даёт
`getPositionMs()`, `getDurationMs()`, `getSpeed()`, `setVolume(float)`; `PlayerUI` — `setButtonState(int, int)`;
`getPlayer()` возвращает `PlaybackView extends PlayerManager extends PlayerEngine, PlayerUI`, так что
все вызовы доступны напрямую.

- [ ] **Шаг 9: живая проверка на X5M `.64`**

```bash
export PATH="$HOME/.local/android-sdk/build-tools/34.0.0:$PATH"
source <(grep -E "storePassword|keyPassword" <путь к keystore.properties> | sed 's/^/export /')
APK=smarttubetv/build/outputs/apk/ststable/release/*.apk
apksigner sign --ks <ваш keystore>.jks --ks-key-alias efir --ks-pass env:STORE_PASS --key-pass env:KEY_PASS $APK
adb connect <адрес тестовой приставки>:5555 && adb -s <адрес приставки>:5555 install -r --no-incremental $APK
```

Проверить руками: открыть англоязычный ролик → нажать «Перевод» → появляется тост с оценкой →
через 1–5 минут звучит русская озвучка, оригинал приглушён, видео не перезапускалось.

- [ ] **Шаг 10: коммит**

```bash
git add common smarttubetv
git commit -m "VOT: кнопка перевода и контроллер синхронизации"
```

---

### Задача 9: Протухшая ссылка и лайв-стримы

**Файлы:**
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/TranslationController.java`
- Тест: `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vot/TranslationSessionTest.java` (дополняется)

**Интерфейсы:**
- Использует: `TranslationSession.start()` (задача 6), `TranslationAudioPlayer.setErrorListener()` (задача 7).

Ссылка на mp3 живёт 2 часа (`X-Amz-Expires=7200`). На длинном ролике второй плеер получит ошибку —
надо перезапросить перевод (он уже в кэше Яндекса, ответ за ~0,4 с) и продолжить с текущей позиции.

- [ ] **Шаг 1: написать падающий тест на повторный запуск сессии**

```java
    @Test
    public void restartResetsDeadlineAndUrl() {
        TranslationSession session = new TranslationSession(600_000);
        session.start(0);
        session.onResult(finished(), 1_000);
        assertNotNull(session.audioUrl());

        session.start(700_000); // перезапуск после протухшей ссылки
        assertNull(session.audioUrl());
        assertEquals(TranslationSession.Decision.WAIT, session.onResult(waiting(5), 700_500));
    }
```

- [ ] **Шаг 2: убедиться, что тест падает**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*TranslationSessionTest*"`
Ожидается: FAIL на `assertNull(session.audioUrl())`, если `start()` не сбрасывает `mAudioUrl`.
Если тест сразу зелёный — поведение уже верное, зафиксировать тест и идти дальше.

- [ ] **Шаг 3: подключить восстановление в контроллере**

В `startAudio()` добавить обработчик ошибки перед `play(...)`:

```java
        mAudioPlayer.setErrorListener(() -> {
            // ссылка на mp3 живёт 2 часа: перезапрашиваем и продолжаем с текущей позиции
            mAudioPlayer.release();
            mAudioPlayer = null;
            disposeSync();
            mSession.start(System.currentTimeMillis());
            poll(0);
        });
```

- [ ] **Шаг 4: не пускать перевод на прямые эфиры**

Кнопка остаётся на месте (она общая для всех роликов), но нажатие на эфире не запускает опрос.
В начало `startTranslation()`, сразу после проверки `mVideoId == null`:

```java
        Video video = getVideo();
        if (video != null && video.isLive) {
            // у Яндекса для эфиров отдельный API с пингами сессии — в этом заходе не поддерживаем
            MessageHelpers.showMessage(getContext(), R.string.vot_live_unsupported);
            getPlayer().setButtonState(R.id.action_translation, PlayerUI.BUTTON_OFF);
            return;
        }
```

Строки в `common/src/main/res/values/strings.xml`:

```xml
    <string name="vot_live_unsupported">Live streams are not supported yet</string>
```

и в `common/src/main/res/values-ru/strings.xml`:

```xml
    <string name="vot_live_unsupported">Прямые эфиры пока не переводятся</string>
```

- [ ] **Шаг 5: собрать и проверить**

Запустить: `./gradlew :common:testStstableDebugUnitTest --tests "*Vot*" --tests "*Translation*"` — PASS,
затем `./gradlew :smarttubetv:assembleStstableRelease -x lintVitalStstableRelease` — BUILD SUCCESSFUL.

- [ ] **Шаг 6: коммит**

```bash
git add common
git commit -m "VOT: восстановление после протухшей ссылки, запрет на эфирах"
```

---

### Задача 10: Настройки перевода

**Файлы:**
- Создать: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/VotData.java`
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/TranslationController.java`
- Изменить: `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/settings/PlayerSettingsPresenter.java`
- Изменить: `common/src/main/res/values/strings.xml`, `common/src/main/res/values-ru/strings.xml`

**Интерфейсы:**
- Отдаёт: `VotData.instance(Context)`; `getOriginalVolume()` → `float` (по умолчанию `0.15f`),
  `setOriginalVolume(float)`, `getTargetLanguage()` → `String` (по умолчанию `"ru"`), `setTargetLanguage(String)`.

- [ ] **Шаг 1: завести класс настроек**

Хранение — как у соседних классов настроек: одна строка в `AppPrefs`, склеенная `Helpers.mergeData`.

```java
package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.sharedutils.helpers.Helpers;

public class VotData {
    private static final String VOT_DATA = "vot_data";
    @SuppressLint("StaticFieldLeak")
    private static VotData sInstance;
    private final AppPrefs mAppPrefs;
    private float mOriginalVolume;
    private String mTargetLanguage;

    private VotData(Context context) {
        mAppPrefs = AppPrefs.instance(context);
        restoreState();
    }

    public static VotData instance(Context context) {
        if (sInstance == null) {
            sInstance = new VotData(context.getApplicationContext());
        }

        return sInstance;
    }

    public float getOriginalVolume() {
        return mOriginalVolume;
    }

    public void setOriginalVolume(float volume) {
        mOriginalVolume = volume;
        persistState();
    }

    public String getTargetLanguage() {
        return mTargetLanguage;
    }

    public void setTargetLanguage(String language) {
        mTargetLanguage = language;
        persistState();
    }

    private void restoreState() {
        String[] split = Helpers.splitData(mAppPrefs.getData(VOT_DATA));

        mOriginalVolume = Helpers.parseFloat(split, 0, 0.15f);
        mTargetLanguage = Helpers.parseStr(split, 1, "ru");
    }

    private void persistState() {
        mAppPrefs.setData(VOT_DATA, Helpers.mergeData(mOriginalVolume, mTargetLanguage));
    }
}
```

Хелперы `Helpers.parseFloat(String[], int, float)`, `Helpers.parseStr(String[], int, String)`,
`Helpers.splitData(String)` и `Helpers.mergeData(Object...)` в этой версии есть — проверено
по `SharedModules/sharedutils/src/main/java/com/liskovsoft/sharedutils/helpers/Helpers.java`.

- [ ] **Шаг 2: использовать настройки в контроллере**

В `TranslationController` убрать константу `DUCKED_VOLUME` и заменить два места:

```java
        getPlayer().setVolume(VotData.instance(getContext()).getOriginalVolume());
```

```java
                .map(ignored -> mClient.translate(videoId, duration, "en",
                        VotData.instance(getContext()).getTargetLanguage()))
```

- [ ] **Шаг 3: добавить категорию в настройки плеера**

В `PlayerSettingsPresenter` добавить метод по образцу `appendOKButtonCategory` (тот же файл, строка 87)
и вызвать его из того же места, откуда вызываются остальные `append*Category`:

```java
    private void appendTranslationCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();
        VotData votData = VotData.instance(getContext());
        float[] volumes = new float[] {0.1f, 0.15f, 0.25f, 0.5f};

        for (float volume : volumes) {
            int percent = (int) (volume * 100);
            options.add(UiOptionItem.from(
                    percent + "%",
                    option -> votData.setOriginalVolume(volume),
                    votData.getOriginalVolume() == volume));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.vot_original_volume), options);
    }
```

- [ ] **Шаг 4: строки**

`common/src/main/res/values/strings.xml`:

```xml
    <string name="vot_original_volume">Original volume under voice-over</string>
```

`common/src/main/res/values-ru/strings.xml`:

```xml
    <string name="vot_original_volume">Громкость оригинала под озвучкой</string>
```

- [ ] **Шаг 5: собрать и проверить на X5M `.64`**

Собрать, подписать, поставить (команды из задачи 8, шаг 9). Проверить: выбор громкости слышен
на слух, значение переживает перезапуск приложения.

- [ ] **Шаг 6: коммит**

```bash
git add common
git commit -m "VOT: настройки громкости оригинала и языка перевода"
```

---

### Задача 11: Итоговая проверка на железе

**Файлы:** правок нет.

- [ ] **Шаг 1: собрать релизный APK и подписать**

Команды из задачи 8, шаг 9. Имя файла: `~/Downloads/efir2-vot.apk`.

- [ ] **Шаг 2: пройти чек-лист на X5M `X5M (тестовая приставка в локальной сети)`**

| Сценарий | Ожидание |
|---|---|
| Ролик, который Яндекс уже переводил | озвучка почти сразу (единицы секунд) |
| Ролик, которого Яндекс не видел | тост с оценкой, озвучка через 1–5 мин, видео не прерывалось |
| Пауза и продолжение | голос замолкает и продолжает с того же места |
| Перемотка вперёд и назад | голос подхватывает новую позицию за ≤1 с |
| Ролик 20+ минут, проверка через 15 минут | рассинхрон на слух не заметен |
| Выключение кнопкой | оригинал возвращает громкость мгновенно, видео не дёргается |
| Смена ролика во время подготовки | «хвост» от прошлого видео не всплывает |
| Прямой эфир | сообщение о невозможности, приложение не падает |

- [ ] **Шаг 3: записать результат в память**

Обновить `~/.claude/projects/-Users-umar/memory/home-media/project_efir2_fork_state_20260904.md`:
что сделано, какие грабли вылезли на железе, где лежит APK. Строку индекса поправить в
`home-media/_INDEX.md`, если изменилось описание.

- [ ] **Шаг 4: коммит и раскатка**

После успешного чек-листа поставить APK на X5M `.54` и SK1 `.108` (командой из задачи 8, шаг 9,
подставив адреса). На Xiaomi `.79` и `.76` **не ставить** — это экраны.
