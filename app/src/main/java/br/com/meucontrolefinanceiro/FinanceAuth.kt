package br.com.meucontrolefinanceiro

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object FinanceAuth {
 private const val METHODS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
 fun available(activity: FragmentActivity): Boolean =
  BiometricManager.from(activity).canAuthenticate(METHODS) == BiometricManager.BIOMETRIC_SUCCESS

 fun unlock(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
  if (!available(activity)) { onError("Configure biometria ou bloqueio de tela seguro nas configurações do Android."); return }
  val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
   override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
   override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError(errString.toString()) }
  })
  prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
   .setTitle("Meu Controle Financeiro")
   .setSubtitle("Confirme sua identidade para acessar seus dados")
   .setAllowedAuthenticators(METHODS).build())
 }
}
