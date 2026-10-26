package androidx.activity.result.contract

abstract class ActivityResultContract<I, O> {
    /** Le résultat sans rien demander à l'utilisateur (le navigateur demandera lui-même). */
    abstract fun grantedResult(input: I): O
}

object ActivityResultContracts {
    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun grantedResult(input: Array<String>): Map<String, Boolean> = input.associateWith { true }
    }
}
