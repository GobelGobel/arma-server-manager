package cz.forgottenempire.servermanager.workshop.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.forgottenempire.servermanager.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Service
@Slf4j
class WorkshopApiMetadataProvider {

    // Now points to ISteamRemoteStorage/GetPublishedFileDetails/v1/ via Constants.STEAM_API_URL
    private static final String REQUEST_URL = Constants.STEAM_API_URL;

    // Kept for backward compatibility with config, but not used by RemoteStorage endpoint.
    @SuppressWarnings("unused")
    private final String steamApiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    WorkshopApiMetadataProvider(@Value("${steam.api.key:}") String steamApiKey, RestTemplate restTemplate) {
        this.steamApiKey = steamApiKey;
        this.restTemplate = restTemplate;
    }

    Optional<ModMetadata> fetchModMetadata(long modId) {
        JsonPropertyProvider propertyProvider = createPropertyProvider(modId);
        if (propertyProvider == null) {
            return Optional.empty();
        }

        String modName = propertyProvider.findName();
        String consumerAppId = propertyProvider.findConsumerAppId();
        if (modName == null || consumerAppId == null) {
            return Optional.empty();
        }

        return Optional.of(new ModMetadata(modName, consumerAppId));
    }

    private JsonPropertyProvider createPropertyProvider(long modId) {
        JsonNode modInfoJson = getModInfoFromSteamApi(modId);
        if (modInfoJson == null) {
            return null;
        }
        return new JsonPropertyProvider(modInfoJson);
    }

    private JsonNode getModInfoFromSteamApi(long modId) {
        try {
            HttpEntity<MultiValueMap<String, String>> request = buildRemoteStorageRequest(modId);

            ResponseEntity<String> response = restTemplate.exchange(
                    REQUEST_URL,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Workshop metadata request failed for mod {}: HTTP {}", modId, response.getStatusCode());
                return null;
            }

            JsonNode parsed = objectMapper.readTree(response.getBody());
            JsonNode responseNode = parsed.path("response");
            JsonNode detailsArray = responseNode.path("publishedfiledetails");

            if (!detailsArray.isArray() || detailsArray.size() == 0) {
                log.error("Workshop metadata response missing publishedfiledetails for mod {}", modId);
                return null;
            }

            return detailsArray.get(0);
        } catch (RestClientException e) {
            log.error("Request to Steam Workshop API for mod ID '{}' failed", modId, e);
            return null;
        } catch (JsonProcessingException e) {
            log.error("Failed to process Workshop API response for mod ID {}", modId, e);
            return null;
        }
    }

    private HttpEntity<MultiValueMap<String, String>> buildRemoteStorageRequest(long modId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("itemcount", "1");
        form.add("publishedfileids[0]", String.valueOf(modId));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        return new HttpEntity<>(form, headers);
    }
}
