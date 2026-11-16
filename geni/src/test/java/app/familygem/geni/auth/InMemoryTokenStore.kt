package app.familygem.geni.auth

/** Token store for tests. */
class InMemoryTokenStore : TokenStore {
    private var token: AuthResult.Success? = null
    override fun get() = token
    override fun save(token: AuthResult.Success) {
        this.token = token
    }
    override fun clear() {
        token = null
    }
}
