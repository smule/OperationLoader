# OperationLoader
A library used for executing tasks based on a dependency graph.

# Usage
There may be multiple instances of the OperationLoader class, each with their own dependency tree.
```java
private final mOperationLoader = new OperationLoader();
```
Adding a task is as simple as adding an ```Operation``` to the ```OperationLoader```:
```java
mOperationLoader.addOperation(new Operation("NETWORK_LOGIN", null) {
  // ... something ...
}
```
This adds an ```Operation``` named ```NETWORK_LOGIN``` that doesn't have any dependencies.

Adding an ```Operation``` with dependencies is similar:
```java
mOperationLoader.addOperation(new Operation("POST_LOGIN_UI_UPDATE",
                              new String [] { "NETWORK_LOGIN", "UI_READY" }) {
  // ... something ...
}
```
It's also possible to assign a priority to an operation to provide a deterministic execution order when multiple operations share the same set of dependencies. Larger ```priority``` values indicate higher priority.
```java
mOperationLoader.addOperation(new Operation("POST_LOGIN_UI_UPDATE",
                              new String [] { "NETWORK_LOGIN", "UI_READY" }, 100) {
  // ... something ...
}
```

# License
```
This software is licensed under the Apache 2 license, quoted below.

Copyright 2016 Smule, Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License. You may obtain a copy of
the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations under
the License.
```
