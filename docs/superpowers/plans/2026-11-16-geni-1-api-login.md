# Geni Sync, Plan 1: API Client and Login — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Log in to Geni from the app with OAuth and fetch the user's own profile through a rate-limited API client.

**Architecture:** Everything lives in module `:geni` (`app.familygem.geni`). A small OkHttp client adds the bearer token and waits on a rate limiter fed by Geni's rate headers. Login uses Geni's mobile OAuth flow (implicit, no client secret) through the system browser and a custom-scheme redirect handled by `GeniLoginActivity`; the token is stored with `EncryptedSharedPreferences`. One `// GENI-HOOK` line adds a menu entry in the trees screen.

**Tech Stack:** Kotlin, OkHttp 5.5.0, Gson 2.14.0, kotlinx-coroutines 1.11.0, androidx.security:security-crypto 1.1.0, JUnit 4, mockwebserver3 5.5.0.

**Spec:** `docs/superpowers/specs/2026-11-16-geni-sync-design.md` (section "Account", constraints on rate limits, secrets and hooks).

**Plan series:** the spec is split into plans that each ship working software:
1. API client and login (this plan).
2. Read path: Geni tree, GEDCOM mapping, lazy load and prefetch.
3. Write path: diff on save, upload queue, temporary IDs.
4. Refresh, conflicts, merged and deleted profiles.
5. Upstream UI rules: hidden features, delete rules, backup restore.

## Global Constraints

- Upstream code is touched only by one-line hooks marked `// GENI-HOOK`, each listed in `geni/UPSTREAM-HOOKS.md`.
- No secrets in git. Credentials come from git-ignored `geni.properties`; the build must work without the file (empty values).
- The app never embeds a Geni client secret: the mobile flow needs only the app ID.
- Rate limits: 40 requests per 10 s with a token, 5 without. Read `X-API-Rate-Limit`, `X-API-Rate-Remaining`, `X-API-Rate-Window` and back off.
- Pass the token as header `Authorization: Bearer <token>`, never as a query parameter.
- `minSdk` 23, Java 8 bytecode with core library desugaring. New code in Kotlin. Resources of `:geni` start with `geni_`.
- Build and test with `JAVA_HOME=/home/vp/jdk ~/.gradle/wrapper/dists/gradle-9.9.0-bin/*/gradle-9.9.0/bin/gradle` (written below as `$GRADLE`).

Geni facts this plan relies on (from the Geni developer docs, 2026-11-16):
- Authorize: `https://<host>/platform/oauth/authorize?client_id=<appId>&redirect_uri=<uri>&response_type=token&display=mobile`.
- Success redirect: `<redirect_uri>#access_token=...&expires_in=...` (may also come as a query string). Denial: `<redirect_uri>?status=unauthorized&message=...`.
- No refresh token in this flow; tokens expire (`expires_in`, example 86400 s). Expired token means logging in again.
- API base `https://<host>/api/`; current user's profile: `GET /api/profile`.

## Review Focus

- Expired or revoked token (HTTP 401) during any call: the token is cleared and the user is asked to log in again; no crash, no retry loop. Test in Task 3.
- User cancels login in the browser (`status=unauthorized`): the login screen shows the message; nothing is stored. Test in Task 2.
- Redirect parameters arrive in the query string instead of the fragment: parsed the same way. Test in Task 2.
- `X-API-Rate-Remaining: 0` or HTTP 429: the client waits for the window and retries once instead of failing. Tests in Tasks 1 and 3.
- Build without `geni.properties`: the app builds and runs; the menu entry opens a screen saying Geni is not configured. Test in Task 4 (`isConfigured`), manual check in Task 5.

---

### Task 1: Config and rate limiter

**Files:**
- Modify: `geni/build.gradle` (properties, dependencies)
- Create: `geni/src/main/java/app/familygem/geni/GeniConfig.kt`
- Create: `geni/src/main/java/app/familygem/geni/api/RateLimiter.kt`
- Modify: `geni/src/main/java/app/familygem/geni/Geni.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/RateLimiterTest.kt`, `geni/src/test/java/app/familygem/geni/GeniTest.kt`

**Interfaces:**
- Produces:
  - `data class GeniConfig(val appId: String, val host: String, val redirectUri: String)` with `val isConfigured: Boolean` (`appId` not blank) and `companion object { fun fromBuildConfig(): GeniConfig }`.
  - `Geni.config: GeniConfig`; `Geni.isConfigured` delegates to it.
  - `class RateLimiter(limit: Int = 40, windowMillis: Long = 10_000, now: () -> Long)` with `suspend fun acquire()` and `fun update(limit: Int?, remaining: Int?, windowSeconds: Int?)`.

- [x] **Step 1: Change `geni/build.gradle`**
  - `geni.properties` keys: `geniAppId`, `geniHost` (default `www.geni.com`; use `sandbox.geni.com` for sandbox), `geniRedirectUri` (default `<geniAppId>://authorize`).
  - Replace `GENI_APP_KEY` with `GENI_HOST` and `GENI_REDIRECT_URI` build config fields. Remove every use of `GENI_APP_KEY`.
  - Add `manifestPlaceholders = [geniRedirectScheme: <scheme part of geniRedirectUri, or "app.familygem.geni.unset" when empty>]`.
  - Dependencies: `implementation` okhttp `5.5.0`, gson `2.14.0`, `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0`, `androidx.security:security-crypto:1.1.0`, `androidx.appcompat:appcompat:1.8.0`; `testImplementation` `com.squareup.okhttp3:mockwebserver3:5.5.0`, `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0`.

- [x] **Step 2: Write failing tests**

```kotlin
// RateLimiterTest
@Test fun allowsLimitRequestsWithoutWaiting() = runTest {
    val limiter = RateLimiter(limit = 3, windowMillis = 10_000) { testScheduler.currentTime }
    repeat(3) { limiter.acquire() }
    assertEquals(0L, testScheduler.currentTime)
}
@Test fun waitsForWindowWhenLimitReached() = runTest {
    val limiter = RateLimiter(limit = 3, windowMillis = 10_000) { testScheduler.currentTime }
    repeat(4) { limiter.acquire() }
    assertEquals(10_000L, testScheduler.currentTime)
}
@Test fun remainingZeroFromHeadersBlocksForWindow() = runTest {
    val limiter = RateLimiter(limit = 40, windowMillis = 10_000) { testScheduler.currentTime }
    limiter.update(limit = 40, remaining = 0, windowSeconds = 10)
    limiter.acquire()
    assertEquals(10_000L, testScheduler.currentTime)
}
@Test fun headersChangeLimit() = runTest {
    val limiter = RateLimiter(limit = 40, windowMillis = 10_000) { testScheduler.currentTime }
    limiter.update(limit = 5, remaining = null, windowSeconds = 10)
    repeat(6) { limiter.acquire() }
    assertEquals(10_000L, testScheduler.currentTime)
}
// GeniTest: replace the existing test
@Test fun notConfiguredWithoutAppId() =
    assertFalse(GeniConfig(appId = "", host = "www.geni.com", redirectUri = "").isConfigured)
```

- [x] **Step 3: Run tests, expect compile failure**

Run: `$GRADLE :geni:testDebugUnitTest`
Expected: FAIL, unresolved `RateLimiter` / `GeniConfig`.

- [x] **Step 4: Implement `GeniConfig` and `RateLimiter`**

`RateLimiter` keeps the timestamps of requests in the current window (sliding window) and suspends with `delay` until the oldest one leaves the window. `update` replaces limit and window when present; `remaining = 0` blocks until one window from now. Guard state with a `Mutex`.

- [x] **Step 5: Run tests, expect pass**

Run: `$GRADLE :geni:testDebugUnitTest`
Expected: PASS, 5 tests.

- [x] **Step 6: Commit**

```bash
git add geni/
git commit -m "Add Geni config and rate limiter"
```

### Task 2: OAuth URL and redirect parsing

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/auth/OAuth.kt`
- Test: `geni/src/test/java/app/familygem/geni/auth/OAuthTest.kt`

**Interfaces:**
- Consumes: `GeniConfig`.
- Produces:
  - `object OAuth { fun authorizeUrl(config: GeniConfig): String; fun parseRedirect(uri: String, nowMillis: Long): AuthResult }`
  - `sealed interface AuthResult { data class Success(val token: String, val expiresAtMillis: Long) : AuthResult; data class Denied(val message: String) : AuthResult; data object Invalid : AuthResult }`

Parse with `okhttp3.HttpUrl` (works in JVM unit tests; `android.net.Uri` does not). For a custom scheme, read the fragment or query manually as `&`-separated, URL-decoded pairs.

- [x] **Step 1: Write failing tests**

```kotlin
private val config = GeniConfig(appId = "123", host = "sandbox.geni.com", redirectUri = "geni123://authorize")

@Test fun authorizeUrlHasMobileImplicitParams() {
    val url = OAuth.authorizeUrl(config).toHttpUrl()
    assertEquals("sandbox.geni.com", url.host)
    assertEquals("/platform/oauth/authorize", url.encodedPath)
    assertEquals("123", url.queryParameter("client_id"))
    assertEquals("geni123://authorize", url.queryParameter("redirect_uri"))
    assertEquals("token", url.queryParameter("response_type"))
    assertEquals("mobile", url.queryParameter("display"))
}
@Test fun parsesTokenFromFragment() = assertEquals(
    AuthResult.Success("abc", 1_000L + 86_400_000L),
    OAuth.parseRedirect("geni123://authorize#access_token=abc&expires_in=86400", nowMillis = 1_000L))
@Test fun parsesTokenFromQuery() = assertEquals(
    AuthResult.Success("abc", 1_000L + 86_400_000L),
    OAuth.parseRedirect("geni123://authorize?access_token=abc&expires_in=86400", nowMillis = 1_000L))
@Test fun parsesDenial() = assertEquals(
    AuthResult.Denied("user canceled"),
    OAuth.parseRedirect("geni123://authorize?status=unauthorized&message=user+canceled", nowMillis = 0L))
@Test fun missingTokenIsInvalid() =
    assertEquals(AuthResult.Invalid, OAuth.parseRedirect("geni123://authorize", nowMillis = 0L))
```

- [x] **Step 2: Run tests, expect failure**

Run: `$GRADLE :geni:testDebugUnitTest --tests '*OAuthTest'`
Expected: FAIL, unresolved `OAuth`.

- [x] **Step 3: Implement `OAuth`**

When `expires_in` is missing, use 86400 s.

- [x] **Step 4: Run tests, expect pass**

Run: `$GRADLE :geni:testDebugUnitTest --tests '*OAuthTest'`
Expected: PASS, 5 tests.

- [x] **Step 5: Commit**

```bash
git add geni/
git commit -m "Add Geni OAuth URL and redirect parsing"
```

### Task 3: Token store and API client

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/auth/TokenStore.kt`
- Create: `geni/src/main/java/app/familygem/geni/api/GeniClient.kt`
- Create: `geni/src/main/java/app/familygem/geni/api/GeniProfile.kt`
- Test: `geni/src/test/java/app/familygem/geni/api/GeniClientTest.kt`

**Interfaces:**
- Consumes: `RateLimiter`, `AuthResult.Success`.
- Produces:
  - `interface TokenStore { fun get(): AuthResult.Success?; fun save(token: AuthResult.Success); fun clear() }`
  - `class EncryptedTokenStore(context: Context) : TokenStore` (`EncryptedSharedPreferences`, file `geni_auth`).
  - `class InMemoryTokenStore : TokenStore` (tests).
  - `class GeniClient(baseUrl: HttpUrl, tokens: TokenStore, limiter: RateLimiter, http: OkHttpClient = OkHttpClient(), now: () -> Long = System::currentTimeMillis)` with `suspend fun currentProfile(): GeniProfile`.
  - `class GeniAuthRequiredException : Exception()`; `class GeniApiException(val code: Int, message: String) : Exception(message)`.
  - `data class GeniProfile(val id: String, val guid: String?, @SerializedName("first_name") val firstName: String?, @SerializedName("last_name") val lastName: String?, val name: String?, @SerializedName("updated_at") val updatedAt: String?)`.

`GeniClient` rules:
- No token, or token past `expiresAtMillis`: throw `GeniAuthRequiredException` without a request.
- Every request: `limiter.acquire()`, header `Authorization: Bearer <token>`, then `limiter.update(...)` from the three rate headers.
- HTTP 401: `tokens.clear()`, throw `GeniAuthRequiredException`.
- HTTP 429: wait one window through the limiter (`update(remaining = 0, ...)`), retry once, then throw `GeniApiException`.
- Other non-2xx: throw `GeniApiException(code, body)`.
- Run network calls on `Dispatchers.IO`.

- [x] **Step 1: Write failing tests** (mockwebserver3, `InMemoryTokenStore`, `runTest`)

```kotlin
@Test fun sendsBearerTokenAndParsesProfile() — enqueue 200 {"id":"profile-1","first_name":"Ann","updated_at":"1700000000"};
    assertEquals("profile-1", client.currentProfile().id);
    assertEquals("Bearer abc", server.takeRequest().headers["Authorization"]);
    assertEquals("/api/profile", recorded request path)
@Test fun noTokenThrowsAuthRequiredWithoutRequest() — empty store; assertThrows GeniAuthRequiredException; assertEquals(0, server.requestCount)
@Test fun expiredTokenThrowsAuthRequired() — token expiresAt < now; same assertions
@Test fun unauthorizedClearsToken() — enqueue 401; assertThrows GeniAuthRequiredException; assertNull(tokens.get())
@Test fun tooManyRequestsRetriesOnce() — enqueue 429 with X-API-Rate-Window: 10, then 200 profile; assertEquals("profile-1", ...); assertEquals(2, server.requestCount)
@Test fun serverErrorThrowsApiException() — enqueue 500; assertEquals(500, assertThrows<GeniApiException>.code)
```

The fake clock for the limiter is `testScheduler.currentTime`; for token expiry pass `now = { 1_000L }`.

- [x] **Step 2: Run tests, expect failure**

Run: `$GRADLE :geni:testDebugUnitTest --tests '*GeniClientTest'`
Expected: FAIL, unresolved `GeniClient`.

- [x] **Step 3: Implement `TokenStore`, `GeniProfile`, `GeniClient`**

- [x] **Step 4: Run tests, expect pass**

Run: `$GRADLE :geni:testDebugUnitTest`
Expected: PASS, all tests of Tasks 1–3.

- [x] **Step 5: Commit**

```bash
git add geni/
git commit -m "Add Geni API client with token store"
```

### Task 4: Login screen and trees menu hook

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/ui/GeniLoginActivity.kt`
- Create: `geni/src/main/res/layout/geni_activity_login.xml`
- Create: `geni/src/main/res/values/geni_strings.xml`, `geni/src/main/res/values-ru/geni_strings.xml`
- Create: `geni/src/main/java/app/familygem/geni/GeniHooks.kt`
- Modify: `geni/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/app/familygem/TreesActivity.kt` (one line after `bar.inflateMenu(R.menu.trees_menu)`, about line 287)
- Modify: `geni/UPSTREAM-HOOKS.md`

**Interfaces:**
- Consumes: `Geni.config`, `OAuth`, `EncryptedTokenStore`, `GeniClient.currentProfile()`, `GeniAuthRequiredException`.
- Produces: `object GeniHooks { fun addTreesMenu(activity: Activity, menu: Menu) }`; `GeniLoginActivity`.

Behavior of `GeniLoginActivity` (`launchMode="singleTask"`, intent filter `VIEW` + `BROWSABLE` + `DEFAULT` on scheme `${geniRedirectScheme}`, host `authorize`):
- Not configured: show `geni_not_configured` text, nothing else.
- Logged out: "Log in to Geni" button opens `OAuth.authorizeUrl` with `Intent.ACTION_VIEW`.
- Redirect in `onCreate`/`onNewIntent`: `OAuth.parseRedirect`; `Success` is saved and the profile is loaded; `Denied` shows its message; `Invalid` shows `geni_login_failed`.
- Logged in: show "Logged in as <name>" from `currentProfile()` and a "Log out" button (`tokens.clear()`). `GeniAuthRequiredException` returns to the logged-out state; other errors show their message.

Hook in `TreesActivity`: `GeniHooks.addTreesMenu(this, bar.menu) // GENI-HOOK`. It adds an item `geni_menu_account` ("Geni account") that starts `GeniLoginActivity`.

Strings (en / ru): `geni_menu_account` Geni account / Аккаунт Geni; `geni_login` Log in to Geni / Войти в Geni; `geni_logout` Log out / Выйти; `geni_logged_in_as` Logged in as %1$s / Вы вошли как %1$s; `geni_not_configured` Geni app ID is not set in geni.properties / В geni.properties не задан ID приложения Geni; `geni_login_failed` Login failed / Не удалось войти.

- [x] **Step 1: Implement activity, layout, strings, manifest entry, `GeniHooks`**
- [x] **Step 2: Add the hook line to `TreesActivity` and a row to `geni/UPSTREAM-HOOKS.md`**
- [x] **Step 3: Build and run all unit tests**

Run: `$GRADLE :geni:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `git grep -n GENI-HOOK` lists `settings.gradle`, `app/build.gradle`, `TreesActivity.kt`.

- [x] **Step 4: Commit**

```bash
git add geni/ app/src/main/java/app/familygem/TreesActivity.kt
git commit -m "Add Geni login screen"
```

### Task 5: Manual check on the sandbox

Needs `geni.properties` with a sandbox app (`geniAppId`, `geniHost=sandbox.geni.com`) and a device or emulator.

- [ ] **Step 1: Without `geni.properties`** — install, open trees menu, "Geni account" shows the not-configured text.
- [x] **Step 2: With sandbox credentials** — log in; the screen shows the sandbox user's name. Cancel once in the browser; the denial message appears.
- [ ] **Step 3: Record findings** in the spec's "To verify on the sandbox" list: whether a numeric app ID works as the redirect scheme (if not, set `geniRedirectUri` to a registered custom scheme), the real `expires_in`, and whether the redirect came as fragment or query.
- [ ] **Step 4: Commit** the spec update: `git commit -m "Record Geni sandbox login findings"`.
