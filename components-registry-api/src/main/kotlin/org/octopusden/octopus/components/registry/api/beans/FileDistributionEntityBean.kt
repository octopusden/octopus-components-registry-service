package org.octopusden.octopus.components.registry.api.beans

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeName
import org.octopusden.octopus.components.registry.api.distribution.entities.FileDistributionEntity
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.net.URI
import java.net.URISyntaxException
import java.util.*
import java.util.regex.Pattern

@JsonTypeName("fileDistribution")
open class FileDistributionEntityBean:
    FileDistributionEntity {
    companion object {
        private val QUERY_ATTRIBUTE_VALUE_VALIDATION_PATTERN = Regex("[a-z,A-Z,0-9,_-]+")
        private const val CLASSIFIER_ATTRIBUTE_KEY = "classifier"
        private const val ARTIFACT_ATTRIBUTE_KEY = "artifactId"
        private val ARTIFACT_ATTRIBUTES = arrayOf(CLASSIFIER_ATTRIBUTE_KEY, ARTIFACT_ATTRIBUTE_KEY)
    }

    private var fileUri: URI
    private var classifier: String? = null
    private var artifactId: String? = null

    @JsonCreator
    constructor(@JsonProperty("uri") fileUri: URI,
                @JsonProperty("classifier") classifier: String?,
                @JsonProperty("artifactId") artifactId: String?) {
        this.fileUri = fileUri
        this.classifier = classifier
        this.artifactId = artifactId
    }

    constructor(distributionItem: String) {
        Objects.requireNonNull(distributionItem)
        try {
            val uri = URI(distributionItem
                .replace("\\\\".toRegex(), "/")
                .replace("\\$".toRegex(), "%24")
                .replace("\\{".toRegex(), "%7B")
                .replace("}".toRegex(), "%7D")
            )
            val query = uri.query
            val uriWOQuery: String
            val attributes: MutableMap<String, String> = HashMap()
            if (query != null) {
                val queryParts = query.split("&").toTypedArray()
                for (queryPart: String in queryParts) {
                    val queryAttributeParts = queryPart.split(Pattern.compile("="), 2)
                    if (queryAttributeParts.size < 2) {
                        throw IllegalStateException("The query specification '$queryPart' doesn't contain value [$distributionItem]")
                    }
                    if (!Arrays.stream(ARTIFACT_ATTRIBUTES).anyMatch{ attribute -> queryAttributeParts[0] == attribute }) {
                        throw IllegalStateException("Unknown query attribute '" + queryAttributeParts[0] + "' in URI '" + distributionItem + "'")
                    }
                    if (!queryAttributeParts[1].matches(QUERY_ATTRIBUTE_VALUE_VALIDATION_PATTERN)) {
                        throw IllegalStateException(
                            "The query '" + queryAttributeParts[0]
                                    + "' attribute value '" + queryAttributeParts[1] + "' contains not allowed symbol(s) [validation pattern: '"
                                    + QUERY_ATTRIBUTE_VALUE_VALIDATION_PATTERN + "']"
                        )
                    }
                    val preValue = attributes.put(queryAttributeParts[0], queryAttributeParts[1])
                    if (preValue != null) {
                        throw IllegalStateException(
                            ("Duplicate query attribute '" + queryAttributeParts[0]
                                    + "' in URI '" + distributionItem + "', previous value " + preValue + ", " + queryAttributeParts[1])
                        )
                    }
                }
                uriWOQuery = uri.toString().replace("?$query", "")
            } else {
                uriWOQuery = uri.toString()
            }
            classifier = attributes[CLASSIFIER_ATTRIBUTE_KEY]
            artifactId = attributes[ARTIFACT_ATTRIBUTE_KEY]
            fileUri = URI(uriWOQuery)
        } catch (exception: URISyntaxException) {
            throw IllegalArgumentException(exception)
        }
    }

    override fun getUri(): URI {
        return fileUri
    }

    override fun getClassifier(): Optional<String> {
        return Optional.ofNullable(classifier)
    }

    override fun getArtifactId(): Optional<String> {
        return Optional.ofNullable(artifactId)
    }

    override fun toString(): String {
        return ("FileDistributionEntity{" +
                "fileUri=" + fileUri +
                ", classifier='" + classifier + '\'' +
                ", artifactId='" + artifactId + '\'' +
                '}')
    }

}