package dev.farid.stabber.client.path

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import java.nio.file.Files
import java.nio.file.Path

object NodeGraphStorage {
    private const val VERSION = 1
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun file(): Path {
        return FabricLoader.getInstance().configDir.resolve("stabber").resolve("manual_nodes.json")
    }

    fun load() {
        val path = file()
        if (!Files.isRegularFile(path)) return
        val root = gson.fromJson(Files.readString(path), JsonObject::class.java) ?: return
        val nodes = LinkedHashMap<Int, ManualNode>()
        val nodesJson = root.getAsJsonArray("nodes") ?: JsonArray()
        for (element in nodesJson) {
            val obj = element.asJsonObject
            val id = obj.get("id").asInt
            val pos = BlockPos(obj.get("x").asInt, obj.get("y").asInt, obj.get("z").asInt)
            val floorY = obj.get("floorY").asDouble
            val kind = PlacementKind.valueOf(obj.get("kind").asString)
            nodes[id] = ManualNode(id, pos, floorY, kind)
        }
        val edges = ArrayList<ManualEdge>()
        val edgesJson = root.getAsJsonArray("edges") ?: JsonArray()
        for (element in edgesJson) {
            val obj = element.asJsonObject
            edges.add(
                ManualEdge(
                    from = obj.get("from").asInt,
                    to = obj.get("to").asInt,
                    move = MoveType.valueOf(obj.get("move").asString),
                ),
            )
        }
        val lastPlaced = if (root.has("lastPlacedId") && !root.get("lastPlacedId").isJsonNull) {
            root.get("lastPlacedId").asInt
        } else {
            null
        }
        ManualNodeGraph.replaceAll(ManualGraphSnapshot(nodes, edges, lastPlaced))
    }

    fun save() {
        val snapshot = ManualNodeGraph.snapshot()
        val root = JsonObject()
        root.addProperty("version", VERSION)
        val nodesJson = JsonArray()
        for (node in snapshot.nodes.values) {
            val obj = JsonObject()
            obj.addProperty("id", node.id)
            obj.addProperty("x", node.pos.x)
            obj.addProperty("y", node.pos.y)
            obj.addProperty("z", node.pos.z)
            obj.addProperty("floorY", node.floorY)
            obj.addProperty("kind", node.kind.name)
            nodesJson.add(obj)
        }
        root.add("nodes", nodesJson)
        val edgesJson = JsonArray()
        for (edge in snapshot.edges) {
            val obj = JsonObject()
            obj.addProperty("from", edge.from)
            obj.addProperty("to", edge.to)
            obj.addProperty("move", edge.move.name)
            edgesJson.add(obj)
        }
        root.add("edges", edgesJson)
        if (snapshot.lastPlacedId != null) {
            root.addProperty("lastPlacedId", snapshot.lastPlacedId)
        } else {
            root.add("lastPlacedId", JsonNull.INSTANCE)
        }
        val path = file()
        Files.createDirectories(path.parent)
        Files.writeString(path, gson.toJson(root))
    }
}
