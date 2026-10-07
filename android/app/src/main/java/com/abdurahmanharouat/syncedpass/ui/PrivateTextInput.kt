package com.abdurahmanharouat.syncedpass.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Keeps what's typed in [content] away from the keyboard maker and autofill:
 * - asks the keyboard (Gboard, Samsung Keyboard…) not to learn from it, like
 *   its incognito mode: no saved words or suggestions, nothing synced to the
 *   keyboard maker's cloud;
 * - removes "Autofill" from the text menu, which would send the field to the
 *   autofill service (Google, Samsung Pass).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun PrivateTextInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val incognito = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
            }
            nextHandler.startInputMethod(incognito)
        },
    ) {
        Box(Modifier.filterTextContextMenuComponents { it.key != TextContextMenuKeys.AutofillKey }) { content() }
    }
}
