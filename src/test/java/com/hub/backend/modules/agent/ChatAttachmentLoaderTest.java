package com.hub.backend.modules.agent;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.DocumentTextExtractor;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import com.hub.backend.infra.llm.ProviderCredentials;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentLoaderTest {

    private static final CurrentUser USER = new CurrentUser(1L, 1L, "demo");

    @Mock
    private FileService files;

    private ChatAttachmentLoader loader;
    private com.hub.backend.infra.llm.AudioTranscriptionClient audioTranscription;

    @BeforeEach
    void setUp() {
        audioTranscription = mock(com.hub.backend.infra.llm.AudioTranscriptionClient.class);
        loader = new ChatAttachmentLoader(files, new DocumentTextExtractor(), audioTranscription);
    }

    @Test
    void expandsPlainTextAttachmentIntoPrompt() {
        stubFile(9L, "notes.txt", "txt", "text/plain", "hello notes".getBytes(StandardCharsets.UTF_8));

        ChatAttachmentLoader.Loaded loaded = loader.loadForNewMessage(
                USER, List.of(9L), null, Set.of(".txt"));

        assertTrue(loaded.hasDocumentText());
        assertTrue(loaded.documentText().contains("hello notes"));
        assertTrue(loaded.documentText().contains("notes.txt"));
    }

    @Test
    void defaultChatAcceptExpandsDocxAndXlsx() throws Exception {
        stubFile(21L, "brief.docx", "docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                sampleDocx("hello docx body"));
        stubFile(22L, "sheet.xlsx", "xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                sampleXlsx("Alpha", "42"));

        ChatAttachmentLoader.Loaded loaded = loader.loadForNewMessage(USER, List.of(21L, 22L));

        assertTrue(loaded.hasDocumentText());
        assertTrue(loaded.documentText().contains("hello docx body"));
        assertTrue(loaded.documentText().contains("brief.docx"));
        assertTrue(loaded.documentText().contains("Alpha"));
        assertTrue(loaded.documentText().contains("42"));
        assertTrue(loaded.documentText().contains("sheet.xlsx"));
    }

    @Test
    void rejectsUnsupportedBinaryTypes() {
        stubFile(3L, "clip.mp4", "mp4", "video/mp4", new byte[] {0, 1, 2});

        assertThrows(InvalidRequestException.class,
                () -> loader.loadForNewMessage(USER, List.of(3L)));
    }

    @Test
    void transcribesAudioAndReturnsItsReference() {
        byte[] bytes = {1, 2, 3};
        stubFile(12L, "practice.wav", "wav", "audio/wav", bytes);
        ProviderCredentials credentials = new ProviderCredentials(
                "OPENAI_COMPATIBLE", null, "relay-key", null, null, null);
        when(audioTranscription.transcribe(any(), eq("practice.wav"), eq("audio/wav"), eq(credentials)))
                .thenReturn("hello there");

        ChatAttachmentLoader.Loaded loaded = loader.loadForNewMessage(
                USER, List.of(12L), credentials, Set.of("audio/*"));

        assertTrue(loaded.hasAudioTranscript());
        assertTrue(loaded.audioTranscript().contains("hello there"));
        assertTrue(loaded.refs().get(0).mimeType().equals("audio/wav"));
        verify(audioTranscription).transcribe(any(), eq("practice.wav"), eq("audio/wav"), eq(credentials));
    }

    @Test
    void rejectsAudioWhenAgentHasNoAudioUploadConfiguration() {
        stubFile(13L, "practice.wav", "wav", "audio/wav", new byte[] {1, 2, 3});

        assertThrows(InvalidRequestException.class,
                () -> loader.loadForNewMessage(USER, List.of(13L)));
    }

    private void stubFile(long id, String name, String ext, String mime, byte[] bytes) {
        UploadedFileEntity row = new UploadedFileEntity(
                USER.userId(), null, "key-" + id, name, mime, ext,
                FileAssetType.CHAT_ATTACHMENT, bytes.length, "checksum");
        assignId(row, id);
        when(files.download(id, USER)).thenReturn(new DownloadedFile(row, new ByteArrayResource(bytes)));
    }

    private static void assignId(UploadedFileEntity row, long id) {
        try {
            var field = UploadedFileEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(row, id);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] sampleDocx(String content) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(content);
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] sampleXlsx(String a1, String b1) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(a1);
            row.createCell(1).setCellValue(b1);
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
