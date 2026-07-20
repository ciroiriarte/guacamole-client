/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

'use strict';

const test = require('node:test');
const assert = require('node:assert');
const { resolveResumeEnabled } =
        require('../src/app/client/types/resumePolicy');

const ATTR = 'enable-session-resume';

test('direct connection opted in resumes', () => {
    assert.strictEqual(
        resolveResumeEnabled(true, { [ATTR]: 'true' }, false), true);
});

test('direct connection explicitly opted out does not resume, even if global is on', () => {
    assert.strictEqual(
        resolveResumeEnabled(true, { [ATTR]: 'false' }, true), false);
});

test('direct connection with a non-"true" attribute value does not resume', () => {
    assert.strictEqual(
        resolveResumeEnabled(true, { [ATTR]: 'yes' }, true), false);
});

test('direct connection with no attribute follows the global default (on)', () => {
    assert.strictEqual(resolveResumeEnabled(true, {}, true), true);
});

test('direct connection with no attribute follows the global default (off)', () => {
    assert.strictEqual(resolveResumeEnabled(true, {}, false), false);
});

test('direct connection whose attributes are not yet loaded follows the global default', () => {
    assert.strictEqual(resolveResumeEnabled(true, null, true), true);
    assert.strictEqual(resolveResumeEnabled(true, undefined, false), false);
});

test('connection group never resumes, even when opted in with global on', () => {
    assert.strictEqual(
        resolveResumeEnabled(false, { [ATTR]: 'true' }, true), false);
});

test('shared/active connection never resumes', () => {
    assert.strictEqual(resolveResumeEnabled(false, null, true), false);
    assert.strictEqual(
        resolveResumeEnabled(false, { [ATTR]: 'true' }, true), false);
});
