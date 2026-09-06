# Патчи к подмодулю MediaServiceCore

`MediaServiceCore` — **чужой** репозиторий (`yuliskov/MediaServiceCore`), пушить туда мы не можем.
Поэтому указатель подмодуля держим строго на апстримном коммите, а свои правки хранятся здесь
патчами и накатываются на рабочее дерево. Так `git clone --recursive` остаётся рабочим:
подмодуль указывает на коммит, который существует в апстриме.

## Накатить все патчи

```sh
scripts/apply-patches.sh
```

Скрипт не падает, если патч уже применён, — тогда он просто сообщает об этом.

## Проверить, применены ли

```sh
git -C MediaServiceCore diff --stat
```

## После обновления подмодуля

Апстрим может починить это у себя — тогда патч перестанет накладываться. Проверить, нужен ли он
ещё, и если нет — удалить файл вместе с этой строкой в списке.

---

## `mediaservicecore-null-guard.patch`

**Что чинит.** В `YouTubeMediaItemService.selectPlaybackFormatInfo()` (появился в апстримном
`0b01a017` от 06.09.2026) результат `getFormatInfoLegacy()` используется без проверки на `null`:

```java
MediaItemFormatInfo formatInfo = getFormatInfoLegacy(videoId, clickTrackingParams);
if (formatInfo.isUnplayable()) {          // ← NPE, когда formatInfo == null
```

А `getFormatInfoLegacy()` возвращает `null` ровно тогда, когда **все** клиенты вернули пусто
(`YouTubeMediaItemFormatInfo.from(null)` → `null`) — то есть в том самом случае, ради которого
запасной путь через watch-страницу и написан. Вместо перехода на него улетает NPE.

**Почему это важно именно нам.** Наша лестница попыток в `ErrorFixerController` опознаёт отказ по
тексту `fromNullable result is null`. У NPE текст другой, он проваливается в общую ветку `else`,
а там — `reloadVideo()` без ограничений. То есть без этого патча возвращается **вечный
крутящийся кружок**, ради устранения которого всё и делалось.

Проверено на SK1 06.09.2026: с патчем ролики `0p38sRz1sT4` и `j88AvPGQSpc` играют, ошибок нет.
