/*
 * Copyright 2022-2024 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sleeper.clients.exception;

import java.util.List;

public class QueryWebSocketClientException extends Exception {
    private List<WebSocketException> exceptions;

    public QueryWebSocketClientException(List<WebSocketException> exceptions) {
        super("WebSocket client encountered " + exceptions.size() + " exceptions while running query",
                exceptions.size() > 0 ? exceptions.get(0) : null);
        this.exceptions = exceptions;
    }

    public List<WebSocketException> getExceptions() {
        return exceptions;
    }
}
