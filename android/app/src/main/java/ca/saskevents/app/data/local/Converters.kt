package ca.saskevents.app.data.local

import androidx.room.TypeConverter
import ca.saskevents.app.data.remote.ArtistLinkDto
import ca.saskevents.app.data.remote.LinkDto
import ca.saskevents.app.data.remote.SourceRefDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun fromStrings(v: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), v)
    @TypeConverter fun toStrings(v: String): List<String> = runCatching { json.decodeFromString(ListSerializer(String.serializer()), v) }.getOrDefault(emptyList())

    @TypeConverter fun fromSources(v: List<SourceRefDto>): String = json.encodeToString(ListSerializer(SourceRefDto.serializer()), v)
    @TypeConverter fun toSources(v: String): List<SourceRefDto> = runCatching { json.decodeFromString(ListSerializer(SourceRefDto.serializer()), v) }.getOrDefault(emptyList())

    @TypeConverter fun fromLinks(v: List<LinkDto>): String = json.encodeToString(ListSerializer(LinkDto.serializer()), v)
    @TypeConverter fun toLinks(v: String): List<LinkDto> = runCatching { json.decodeFromString(ListSerializer(LinkDto.serializer()), v) }.getOrDefault(emptyList())

    @TypeConverter fun fromArtistLinks(v: List<ArtistLinkDto>): String = json.encodeToString(ListSerializer(ArtistLinkDto.serializer()), v)
    @TypeConverter fun toArtistLinks(v: String): List<ArtistLinkDto> = runCatching { json.decodeFromString(ListSerializer(ArtistLinkDto.serializer()), v) }.getOrDefault(emptyList())
}
