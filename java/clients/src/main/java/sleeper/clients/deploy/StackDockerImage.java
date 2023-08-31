/*
 * Copyright 2022-2023 Crown Copyright
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

package sleeper.clients.deploy;

public class StackDockerImage {
    private final String stackName;
    private final String imageName;
    private final boolean isBuildx;

    private StackDockerImage(Builder builder) {
        stackName = builder.stackName;
        imageName = builder.imageName;
        isBuildx = builder.isBuildx;
    }

    public static StackDockerImage dockerBuildImage(String stackName, String imageName) {
        return builder().stackName(stackName).imageName(imageName).build();
    }

    public static StackDockerImage dockerBuildxImage(String stackName, String imageName) {
        return builder().stackName(stackName).imageName(imageName).isBuildx(true).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getStackName() {
        return stackName;
    }

    public String getImageName() {
        return imageName;
    }

    public boolean isBuildx() {
        return isBuildx;
    }

    public static final class Builder {
        private String stackName;
        private String imageName;
        private boolean isBuildx;

        private Builder() {
        }

        public Builder stackName(String stackName) {
            this.stackName = stackName;
            return this;
        }

        public Builder imageName(String imageName) {
            this.imageName = imageName;
            return this;
        }

        public Builder isBuildx(boolean isBuildx) {
            this.isBuildx = isBuildx;
            return this;
        }

        public StackDockerImage build() {
            return new StackDockerImage(this);
        }
    }
}
