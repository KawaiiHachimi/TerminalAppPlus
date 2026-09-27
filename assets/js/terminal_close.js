/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

(function() {
    if (window.ttydSocket && typeof window.ttydSocket.close === 'function') {
        console.log('[TerminalApp] Closing intercepted ttydSocket (1000)');
        // Close with code 1000 (CLOSE_NORMAL) to prevent ttyd from reconnecting.
        // As seen in ttyd's html/src/components/terminal/xterm/index.ts:
        // if (event.code !== 1000 && doReconnect) { ... refreshToken().then(connect); }
        window.ttydSocket.close(1000);
    } else {
        console.log('[TerminalApp] ttydSocket not found or already closed');
    }
})();
