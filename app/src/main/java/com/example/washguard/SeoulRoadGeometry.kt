package com.example.washguard

import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.*

data class RoadXY(val x:Float,val y:Float)
fun roadProject(lat:Double,lon:Double)=RoadXY(((lon-127.0)*111320*cos(Math.toRadians(37.56))).toFloat(),((lat-37.56)*111320).toFloat())
data class OfflineRoad(val kind:Int,val xy:FloatArray) {
    val minX:Float;val maxX:Float;val minY:Float;val maxY:Float
    init {
        var a=Float.POSITIVE_INFINITY;var b=Float.NEGATIVE_INFINITY;var c=a;var d=b
        for(i in xy.indices step 2){a=minOf(a,xy[i]);b=maxOf(b,xy[i]);c=minOf(c,xy[i+1]);d=maxOf(d,xy[i+1])}
        minX=a;maxX=b;minY=c;maxY=d
    }
}
class SeoulRoadGeometry(val boundary:List<FloatArray>,val roads:List<OfflineRoad>) {
    private val cells=HashMap<Long,MutableList<Int>>()
    private fun key(x:Int,y:Int)=(x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    init {roads.forEachIndexed {i,r ->
        for(x in floor(r.minX/1000).toInt()..floor(r.maxX/1000).toInt())
            for(y in floor(r.minY/1000).toInt()..floor(r.maxY/1000).toInt()) cells.getOrPut(key(x,y)){mutableListOf()}.add(i)
    }}
    fun inside(p:RoadXY):Boolean = boundary.any {ring ->
        var inside=false;var j=ring.size/2-1
        for(i in 0 until ring.size/2) {
            val xi=ring[i*2];val yi=ring[i*2+1];val xj=ring[j*2];val yj=ring[j*2+1]
            if((yi>p.y)!=(yj>p.y) && p.x<(xj-xi)*(p.y-yi)/(yj-yi)+xi)inside=!inside
            j=i
        };inside
    }
    fun nearby(p:RoadXY,radius:Float=1800f):List<OfflineRoad> {
        val ids=HashSet<Int>()
        for(x in floor((p.x-radius)/1000).toInt()..floor((p.x+radius)/1000).toInt())
            for(y in floor((p.y-radius)/1000).toInt()..floor((p.y+radius)/1000).toInt()) cells[key(x,y)]?.let {ids.addAll(it)}
        return ids.asSequence().map {roads[it]}.filter {it.maxX>=p.x-radius&&it.minX<=p.x+radius&&it.maxY>=p.y-radius&&it.minY<=p.y+radius}.sortedByDescending {it.kind}.toList()
    }
    companion object {
        fun read(input:InputStream):SeoulRoadGeometry = DataInputStream(GZIPInputStream(input)).use {s ->
            require(s.readInt()==0x50465244 && s.readInt()==1){"Unsupported road pack"}
            fun points():FloatArray {val count=s.readInt();require(count in 2..100000);return FloatArray(count*2){s.readFloat().also {require(it.isFinite())}}}
            val rings=s.readInt();require(rings in 1..100)
            val boundary=List(rings){points()}
            val count=s.readInt();require(count in 1000..300000)
            SeoulRoadGeometry(boundary,List(count){val kind=s.readUnsignedByte();require(kind in 0..5);OfflineRoad(kind,points())})
        }
    }
}
