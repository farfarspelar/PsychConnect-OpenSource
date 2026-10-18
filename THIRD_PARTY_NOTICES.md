# Third-party notices

This file records the third-party software and asset review for the source tree and current Android runtime dependency graph. Dependency versions are pinned/resolved by `app/build.gradle.kts`, Gradle plugin declarations, `server/package.json`, and `server/package-lock.json`. Package license identifiers below come from the checked-in npm lockfile. Third-party dependencies retain their own licenses; the PsychConnect MIT license does not replace them.

## Android runtime and build dependencies

| Component | Version / scope | License and notice |
|---|---|---|
| AndroidX Core and transitive AndroidX libraries | `androidx.core:core:1.15.0` plus resolved AndroidX dependencies | Apache License 2.0; [license text](https://www.apache.org/licenses/LICENSE-2.0) |
| OkHttp | `com.squareup.okhttp3:okhttp:4.12.0` | Apache License 2.0; [license text](https://www.apache.org/licenses/LICENSE-2.0) |
| Okio | `com.squareup.okio:okio:3.6.0` (transitive) | Apache License 2.0; [license text](https://www.apache.org/licenses/LICENSE-2.0) |
| Kotlin standard library and JetBrains annotations | Kotlin `2.2.20`; resolved annotations `13.0` | Apache License 2.0; [license text](https://www.apache.org/licenses/LICENSE-2.0) |
| JUnit | `junit:junit:4.13.2` (tests) | Eclipse Public License 1.0; [license text](https://www.eclipse.org/legal/epl-v10.html) |
| Hamcrest Core | `org.hamcrest:hamcrest-core:1.3` (JUnit test dependency) | BSD License; copyright notice: `Copyright (c) 2000-2006, www.hamcrest.org`; the artifact includes its BSD license text |
| Gradle Wrapper / Gradle scripts | Gradle `8.13`; wrapper JAR and scripts are in the repository | Apache License 2.0; [license text](https://www.apache.org/licenses/LICENSE-2.0) |
| Android Gradle Plugin / Kotlin Gradle Plugin | `8.13.2` / `2.2.20` (build tools) | Apache License 2.0; [license text](https://www.apache.org/licenses/LICENSE-2.0) |

Android SDK Platform and Build Tools are downloaded by each developer and are not stored in this repository. They are subject to the Android SDK license presented by Google. Android system fonts are used; no third-party font files or icon library are bundled.

## Node.js server dependencies

The server installs dependencies from the npm registry using the lockfile; `node_modules/` is not committed. The direct production dependencies are `pg@8.23.1` (MIT) and `ws@8.22.0` (MIT). The development-only test database is `pg-mem@3.0.14` (MIT). The following lockfile inventory includes direct, transitive, and development packages. Their own package metadata contains the corresponding notices/license text.

### BSD-3-Clause (1 packages)

`moo@0.5.3`.

### CC0-1.0 (1 packages)

`railroad-diagrams@1.0.0`.

### ISC (4 packages)

`lru-cache@6.0.0`, `pg-int8@1.0.1`, `split2@4.2.0`, `yallist@4.0.0`.

### MIT (44 packages)

`call-bind-apply-helpers@1.0.2`, `call-bind@1.0.9`, `call-bound@1.0.4`, `commander@2.20.3`, `define-data-property@1.1.4`, `discontinuous-range@1.0.0`, `dunder-proto@1.0.1`, `es-define-property@1.0.1`, `es-errors@1.3.0`, `es-object-atoms@1.1.2`, `function-bind@1.1.2`, `functional-red-black-tree@1.0.1`, `get-intrinsic@1.3.0`, `get-proto@1.0.1`, `gopd@1.2.0`, `has-property-descriptors@1.0.2`, `has-symbols@1.1.0`, `hasown@2.0.4`, `immutable@4.3.9`, `isarray@2.0.5`, `json-stable-stringify@1.3.0`, `math-intrinsics@1.1.0`, `moment@2.31.0`, `nearley@2.20.1`, `object-hash@2.2.0`, `object-keys@1.1.1`, `pg-cloudflare@1.4.1`, `pg-connection-string@2.14.1`, `pg-mem@3.0.14`, `pg-pool@3.14.0`, `pg-protocol@1.16.1`, `pg-types@2.2.0`, `pg@8.23.1`, `pgpass@1.0.5`, `pgsql-ast-parser@12.0.2`, `postgres-array@2.0.0`, `postgres-bytea@1.0.1`, `postgres-date@1.0.7`, `postgres-interval@1.2.0`, `randexp@0.4.6`, `ret@0.1.15`, `set-function-length@1.2.2`, `ws@8.22.0`, `xtend@4.0.2`.

### Public Domain (1 packages)

`jsonify@0.0.1`.

License reference links: [MIT](https://opensource.org/license/mit), [ISC](https://opensource.org/license/isc-license-txt), [BSD 3-Clause](https://opensource.org/license/bsd-3-clause), [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). `jsonify@0.0.1` declares Public Domain in npm metadata.

## Project artwork and audio

PsychConnect's generated artwork is separate from the MIT-licensed source code and is offered under the intended CC BY 4.0 terms documented in [ARTWORK_LICENSE.md](ARTWORK_LICENSE.md). The license is limited to rights that the maintainer may license under applicable law and makes no claim of uniqueness or exclusive copyright.

The current procedural menu sound `app/src/main/res/raw/button_click.wav` is offered under CC0 1.0. Its generator and provenance are documented in [AUDIO_LICENSE.md](AUDIO_LICENSE.md) and [tools/generate_button_click.py](tools/generate_button_click.py). No third-party recordings or samples are used. The earlier unknown-provenance WAV has been removed from rewritten publication history. No background music is included.
