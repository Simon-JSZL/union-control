package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.MemoryStoreMapper;
import com.union.control.service.impl.MemoryStoreServiceImpl;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MemoryStoreServiceImplTest {
    @Test
    public void parsesAuthenticatedUserFromJsonBeforeCallingMapper() {
        MemoryStoreMapper mapper = mock(MemoryStoreMapper.class);
        MemoryStoreServiceImpl service = new MemoryStoreServiceImpl(mapper, new ObjectMapper());
        String path = TestJson.USER_ID + "/personal/notes.md";
        when(mapper.readMemory(TestJson.USER_ID, path)).thenReturn(Collections.emptyList());

        service.memoryRead(TestJson.request("path", path, "maxChars", 100));

        verify(mapper).readMemory(TestJson.USER_ID, path);
    }

    @Test
    public void writeAndDeleteKeepVersionsAndOperationReceipts() {
        MemoryStoreMapper mapper = mock(MemoryStoreMapper.class);
        MemoryStoreServiceImpl service = new MemoryStoreServiceImpl(mapper, new ObjectMapper());
        String path = TestJson.USER_ID + "/personal/notes.md";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", path);
        payload.put("content", "notes");
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("id", "write-1");
        operation.put("fingerprint", "fp-1");
        payload.put("operation", operation);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", 7L);
        first.put("version", 1L);
        Map<String, Object> second = new LinkedHashMap<>(first);
        second.put("version", 2L);
        when(mapper.findMemoryRow(TestJson.USER_ID, path)).thenReturn(
                Collections.emptyList(), Collections.singletonList(first), Collections.singletonList(second));
        when(mapper.currentMemoryVersion(TestJson.USER_ID, path)).thenReturn(0L, 1L);

        assertThat((Map<String, Object>) service.memoryWrite(TestJson.request(payload)).get("mutation"))
                .containsEntry("version", "1").containsEntry("existed", false);
        verify(mapper).insertMemoryFile(TestJson.USER_ID, path, "notes", 1L, "write-1");
        verify(mapper).insertMemoryOperation(TestJson.USER_ID, "write-1", "fp-1", 1L, 0);

        operation.put("id", "write-2");
        payload.put("expectedVersion", "1");
        assertThat((Map<String, Object>) service.memoryWrite(TestJson.request(payload)).get("mutation"))
                .containsEntry("version", "2").containsEntry("existed", true);
        verify(mapper).updateMemoryFile("notes", 2L, "write-2", 7L);
        verify(mapper).insertMemoryOperation(TestJson.USER_ID, "write-2", "fp-1", 2L, 1);

        operation.put("id", "delete-1");
        payload.put("expectedVersion", "2");
        assertThat((Map<String, Object>) service.memoryDelete(TestJson.request(payload)).get("mutation"))
                .containsEntry("version", null).containsEntry("existed", true);
        verify(mapper).softDeleteMemoryFile(7L);
        verify(mapper).insertMemoryOperation(TestJson.USER_ID, "delete-1", "fp-1", null, 1);
    }

    @Test
    public void writeAndDeleteStopOnVersionConflictsAndReplayReceipts() {
        for (boolean delete : new boolean[]{false, true}) {
            MemoryStoreMapper mapper = mock(MemoryStoreMapper.class);
            MemoryStoreServiceImpl service = new MemoryStoreServiceImpl(mapper, new ObjectMapper());
            String path = TestJson.USER_ID + "/personal/notes.md";
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("id", "op-1");
            operation.put("fingerprint", "fp-1");
            String input = TestJson.request("path", path, "content", "notes", "expectedVersion", "0",
                    "operation", operation);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", 7L);
            row.put("version", 1L);
            when(mapper.findMemoryRow(TestJson.USER_ID, path))
                    .thenReturn(Collections.singletonList(row));

            assertThat(delete ? service.memoryDelete(input) : service.memoryWrite(input))
                    .containsEntry("errorCode", "version_conflict");
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("fingerprint", "fp-1");
            receipt.put("resultVersion", 1L);
            receipt.put("existed", 1);
            when(mapper.findMemoryOperation(TestJson.USER_ID, "op-1", true))
                    .thenReturn(Collections.singletonList(receipt));
            Map<String, Object> replay = delete ? service.memoryDelete(input) : service.memoryWrite(input);
            assertThat((Map<String, Object>) replay.get("mutation"))
                    .containsEntry("version", "1").containsEntry("replayed", true);
            receipt.put("fingerprint", "other");
            assertThat(delete ? service.memoryDelete(input) : service.memoryWrite(input))
                    .containsEntry("errorCode", "operation_conflict");
            verify(mapper, times(1)).findMemoryRow(TestJson.USER_ID, path);
            verify(mapper, never()).insertMemoryFile(anyString(), anyString(), anyString(), anyLong(), anyString());
            verify(mapper, never()).updateMemoryFile(anyString(), anyLong(), anyString(), any());
            verify(mapper, never()).softDeleteMemoryFile(any());
            verify(mapper, never()).insertMemoryOperation(anyString(), anyString(), anyString(), any(), anyInt());
        }
    }

    @Test public void readListOperationAndSearchPreserveBoundsAndOwnership() throws Exception {
        MemoryStoreMapper mapper=mock(MemoryStoreMapper.class); ObjectMapper json=new ObjectMapper();
        MemoryStoreServiceImpl service=new MemoryStoreServiceImpl(mapper,json); String path=TestJson.USER_ID+"/personal/note";
        when(mapper.readMemory(TestJson.USER_ID,path)).thenReturn(Collections.singletonList(json.readValue("{\"content\":\"abcdef\",\"version\":2,\"operationId\":\"op\"}",Map.class)));
        assertThat(json.valueToTree(service.memoryRead(TestJson.request("path",path,"maxChars",3))).at("/file/content").asText()).isEqualTo("abc");
        assertThat(json.valueToTree(service.memoryRead(TestJson.request("path",path,"maxChars",10))).at("/file/truncated").asBoolean()).isFalse();
        service.memoryList(TestJson.request("limit",10)); service.memoryList(TestJson.request("prefix",TestJson.USER_ID+"/personal/n","limit",10));
        verify(mapper).listMemoryPaths(TestJson.USER_ID,TestJson.USER_ID+"/personal/%",10);
        assertThat(service.memoryOperation(TestJson.request("operationId","op","fingerprint","fp"))).containsEntry("mutation",null);
        Map<String,Object> receipt=json.readValue("{\"fingerprint\":\"fp\",\"resultVersion\":2,\"existed\":0}",Map.class);
        when(mapper.findMemoryOperation(TestJson.USER_ID,"op",false)).thenReturn(Collections.singletonList(receipt));
        assertThat(json.valueToTree(service.memoryOperation(TestJson.request("operationId","op","fingerprint","fp"))).at("/mutation/replayed").asBoolean()).isTrue();
        Map<String,Object> match=json.readValue("{\"path\":\"note\",\"content\":\"ABCdef\"}",Map.class);
        Map<String,Object> miss=json.readValue("{\"path\":\"other\",\"content\":\"zzz\"}",Map.class);
        when(mapper.searchMemory(TestJson.USER_ID,TestJson.USER_ID+"/personal/%",3)).thenReturn(java.util.Arrays.asList(miss,match,match));
        String search=TestJson.request("query","abc","limit",10,"maxFiles",2,"maxChars",100,"maxFileChars",4);
        com.fasterxml.jackson.databind.JsonNode result=json.valueToTree(service.memorySearch(search)).get("result");
        assertThat(result.get("truncated").asBoolean()).isTrue(); assertThat(result.get("matches").size()).isEqualTo(1);
        assertThat(result.at("/matches/0/snippet").asText()).isEqualTo("ABCd");
        when(mapper.searchMemory(TestJson.USER_ID,TestJson.USER_ID+"/personal/%",3)).thenReturn(Collections.singletonList(match));
        service.memorySearch(TestJson.request("query","note","limit",1,"maxFiles",2,"maxChars",2,"maxFileChars",10));
    }

    @Test public void optionalReceiptsAndAbsentDeletesKeepOriginalMutationSemantics() {
        MemoryStoreMapper mapper=mock(MemoryStoreMapper.class); MemoryStoreServiceImpl service=new MemoryStoreServiceImpl(mapper,new ObjectMapper());
        String path=TestJson.USER_ID+"/personal/note";
        assertThat((Map<String,Object>)service.memoryDelete(TestJson.request("path",path)).get("mutation")).containsEntry("existed",false);
        when(mapper.currentMemoryVersion(TestJson.USER_ID,path)).thenReturn(null);
        service.memoryWrite(TestJson.request("path",path,"content","text"));
        verify(mapper).insertMemoryFile(TestJson.USER_ID,path,"text",1L,null);
        verify(mapper,never()).insertMemoryOperation(anyString(),anyString(),anyString(),any(),anyInt());
        for (String invalid:new String[]{"other/personal/note",TestJson.USER_ID+"/personal/../note",TestJson.USER_ID+"/personal/bad space"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.memoryDelete(TestJson.request("path",invalid))).isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.memoryList(TestJson.request("prefix","../bad","limit",1))).isInstanceOf(IllegalArgumentException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.memoryWrite(TestJson.request("path",path,"content","text","operation","invalid"))).isInstanceOf(IllegalArgumentException.class);
    }


    @Test public void searchStopsAtFileAndCharacterLimitsAndUpdatesWorkWithoutReceipts() throws Exception {
        MemoryStoreMapper mapper=mock(MemoryStoreMapper.class); MemoryStoreServiceImpl service=new MemoryStoreServiceImpl(mapper,new ObjectMapper());
        String path=TestJson.USER_ID+"/personal/note";
        Map<String,Object> row=new LinkedHashMap<>(); row.put("id",7L); row.put("version",1L); row.put("path",path); row.put("content","notes");
        when(mapper.findMemoryRow(TestJson.USER_ID,path)).thenReturn(Collections.singletonList(row));
        when(mapper.currentMemoryVersion(TestJson.USER_ID,path)).thenReturn(1L);
        service.memoryWrite(TestJson.request("path",path,"content","new","expectedVersion","1"));
        verify(mapper).updateMemoryFile("new",2L,null,7L);
        when(mapper.searchMemory(TestJson.USER_ID,TestJson.USER_ID+"/personal/%",3)).thenReturn(java.util.Arrays.asList(row,row));
        assertThat(new ObjectMapper().valueToTree(service.memorySearch(TestJson.request("query","note","limit",1,"maxFiles",2,"maxChars",100,"maxFileChars",10))).at("/result/matches").size()).isEqualTo(1);
        assertThat(new ObjectMapper().valueToTree(service.memorySearch(TestJson.request("query","note","limit",10,"maxFiles",2,"maxChars",1,"maxFileChars",10))).at("/result/matches/0/snippet").asText()).isEqualTo("n");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.memoryList(TestJson.request("prefix",TestJson.USER_ID+"/personal/../bad","limit",1))).isInstanceOf(IllegalArgumentException.class);
    }

}
