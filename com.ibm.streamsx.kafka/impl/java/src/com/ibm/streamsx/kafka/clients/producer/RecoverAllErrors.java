/*
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ibm.streamsx.kafka.clients.producer;

/**
 * This class treats all Exceptiona as recoverable.
 * @author The IBM Kafka toolkit maintainers
 * @sinve toolkit version 2.2
 */
public class RecoverAllErrors implements ErrorCategorizer {

    /**
     * Returns always RECOVERABLE
     * @see com.ibm.streamsx.kafka.clients.producer.ErrorCategorizer#isRecoverable(java.lang.Exception)
     */
    @Override
    public ErrCategory isRecoverable(Exception e) {
        return ErrCategory.RECOVERABLE;
    }
}
