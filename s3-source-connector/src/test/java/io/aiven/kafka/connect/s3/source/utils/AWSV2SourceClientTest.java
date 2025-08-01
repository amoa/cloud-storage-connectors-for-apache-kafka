/*
 * Copyright 2024 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.connect.s3.source.utils;

import static io.aiven.kafka.connect.config.s3.S3ConfigFragment.AWS_S3_BUCKET_NAME_CONFIG;
import static io.aiven.kafka.connect.config.s3.S3ConfigFragment.AWS_S3_PREFIX_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.aiven.kafka.connect.common.config.FileNameFragment;
import io.aiven.kafka.connect.s3.source.config.S3SourceConfig;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

class AWSV2SourceClientTest {

    private S3Client s3Client;

    private AWSV2SourceClient awsv2SourceClient;

    @Captor
    ArgumentCaptor<ListObjectsV2Request> requestCaptor;

    private static Map<String, String> getConfigMap() {
        final Map<String, String> configMap = new HashMap<>();

        configMap.put(AWS_S3_BUCKET_NAME_CONFIG, "test-bucket");
        FileNameFragment.setter(configMap).template(".*");
        return configMap;
    }

    @Test
    void testFetchObjectSummariesWithNoObjects() {
        initializeWithTaskConfigs();
        final ListObjectsV2Response listObjectsV2Response = createListObjectsV2Response(Collections.emptyList(), null);
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listObjectsV2Response);

        final Iterator<String> summaries = awsv2SourceClient.getListOfObjectKeys(null);
        assertThat(summaries).isExhausted();
    }

    @Test
    void testFetchObjectSummariesWithOneObjectWithBasicConfig() {
        final String objectKey = "any-key";

        initializeWithTaskConfigs();
        final Iterator<String> summaries = getS3ObjectKeysIterator(objectKey);
        assertThat(summaries).hasNext();
    }

    @Test
    void testFetchObjectSummariesWithZeroByteObject() {
        initializeWithTaskConfigs();
        final ListObjectsV2Response listObjectsV2Response = getListObjectsV2Response();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listObjectsV2Response);

        final Iterator<String> summaries = awsv2SourceClient.getListOfObjectKeys(null);

        assertThat(summaries.next()).isNotBlank();
        assertThat(summaries.next()).isNotBlank();
        assertThat(summaries).isExhausted();
    }

    @Test
    void testFetchObjectSummariesWithPagination() throws IOException {
        initializeWithTaskConfigs();
        final S3Object object1 = createObjectSummary(1, "key1");
        final S3Object object2 = createObjectSummary(2, "key2");
        final List<S3Object> firstBatch = List.of(object1);
        final List<S3Object> secondBatch = List.of(object2);

        final ListObjectsV2Response firstResult = createListObjectsV2Response(firstBatch, "nextToken");
        final ListObjectsV2Response secondResult = createListObjectsV2Response(secondBatch, null);

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(firstResult).thenReturn(secondResult);

        final Iterator<String> summaries = awsv2SourceClient.getListOfObjectKeys(null);
        verify(s3Client, times(1)).listObjectsV2(any(ListObjectsV2Request.class));
        assertThat(summaries.next()).isNotNull();
        assertThat(summaries.next()).isNotNull();
    }

    @Test
    void testFetchObjectWithPrefix() {
        final Map<String, String> configMap = getConfigMap();
        configMap.put(AWS_S3_PREFIX_CONFIG, "test/");
        final S3SourceConfig s3SourceConfig = new S3SourceConfig(configMap);
        s3Client = mock(S3Client.class);
        awsv2SourceClient = new AWSV2SourceClient(s3Client, s3SourceConfig);
        requestCaptor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        final S3Object object1 = createObjectSummary(1, "topics/key1/1/key1.txt");
        final S3Object object2 = createObjectSummary(1, "topics/key2/2/key2.txt");

        final ListObjectsV2Response firstResult = createListObjectsV2Response(List.of(object1), "nextToken");
        final ListObjectsV2Response secondResult = createListObjectsV2Response(List.of(object2), null);

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(firstResult).thenReturn(secondResult);

        final Iterator<String> summaries = awsv2SourceClient.getListOfObjectKeys(null);
        verify(s3Client, times(1)).listObjectsV2(any(ListObjectsV2Request.class));

        assertThat(summaries.next()).isNotNull();
        assertThat(summaries.next()).isNotNull();

        verify(s3Client, times(2)).listObjectsV2(requestCaptor.capture());
        final List<ListObjectsV2Request> allRequests = requestCaptor.getAllValues();
        assertThat(summaries).isExhausted();

        assertThat(allRequests.get(0).prefix()).isEqualTo(s3SourceConfig.getAwsS3Prefix());
        // Not required with continuation token
        assertThat(allRequests.get(1).prefix()).isNull();
        assertThat(allRequests.get(1).continuationToken()).isEqualTo("nextToken");
    }

    @Test
    void testFetchObjectWithInitialStartAfter() {
        final Map<String, String> configMap = getConfigMap();
        final String startAfter = "file-option-1-12000.txt";
        final S3SourceConfig s3SourceConfig = new S3SourceConfig(configMap);
        s3Client = mock(S3Client.class);
        awsv2SourceClient = new AWSV2SourceClient(s3Client, s3SourceConfig);
        requestCaptor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        final S3Object object1 = createObjectSummary(1, "key1-1-10000");
        final S3Object object2 = createObjectSummary(1, "key2-2-20000");

        final ListObjectsV2Response firstResult = createListObjectsV2Response(List.of(object1), "nextToken");
        final ListObjectsV2Response secondResult = createListObjectsV2Response(List.of(object2), null);

        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(firstResult).thenReturn(secondResult);

        final Iterator<String> summaries = awsv2SourceClient.getListOfObjectKeys(startAfter);
        verify(s3Client, times(1)).listObjectsV2(any(ListObjectsV2Request.class));

        assertThat(summaries.next()).isNotNull();
        assertThat(summaries.next()).isNotNull();

        verify(s3Client, times(2)).listObjectsV2(requestCaptor.capture());
        final List<ListObjectsV2Request> allRequests = requestCaptor.getAllValues();
        assertThat(summaries).isExhausted();

        assertThat(allRequests.get(0).startAfter()).isEqualTo(startAfter);
        // Not required with continuation token
        assertThat(allRequests.get(1).startAfter()).isNull();
        assertThat(allRequests.get(1).continuationToken()).isEqualTo("nextToken");

    }

    private ListObjectsV2Response createListObjectsV2Response(final List<S3Object> summaries, final String nextToken) {
        final ListObjectsV2Response result = mock(ListObjectsV2Response.class);
        when(result.contents()).thenReturn(summaries);
        when(result.nextContinuationToken()).thenReturn(nextToken);
        when(result.isTruncated()).thenReturn(nextToken != null);
        return result;
    }

    private S3Object createObjectSummary(final long sizeOfObject, final String objectKey) {
        final S3Object summary = mock(S3Object.class);
        when(summary.size()).thenReturn(sizeOfObject);
        when(summary.key()).thenReturn(objectKey);
        return summary;
    }

    private Iterator<String> getS3ObjectKeysIterator(final String objectKey) {
        final S3Object objectSummary = createObjectSummary(1, objectKey);
        final ListObjectsV2Response listObjectsV2Result = createListObjectsV2Response(
                Collections.singletonList(objectSummary), null);
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listObjectsV2Result);

        return awsv2SourceClient.getListOfObjectKeys(null);
    }

    private void initializeWithTaskConfigs() {
        final Map<String, String> configMap = getConfigMap();
        final S3SourceConfig s3SourceConfig = new S3SourceConfig(configMap);
        s3Client = mock(S3Client.class);
        awsv2SourceClient = new AWSV2SourceClient(s3Client, s3SourceConfig);
    }

    private ListObjectsV2Response getListObjectsV2Response() {
        final S3Object zeroByteObject = createObjectSummary(0, "key1");
        final S3Object nonZeroByteObject1 = createObjectSummary(1, "key2");
        final S3Object nonZeroByteObject2 = createObjectSummary(1, "key3");
        return createListObjectsV2Response(List.of(zeroByteObject, nonZeroByteObject1, nonZeroByteObject2), null);
    }
}
