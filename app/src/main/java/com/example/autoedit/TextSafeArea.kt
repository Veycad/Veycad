package com.veycad.app

data class TextBoxRequest(val id:String,val position:TextPosition,val height:Float)
data class TextPlacedBox(val id:String,val left:Float,val top:Float,val right:Float,val bottom:Float)

/** Rectangles include padding, plate and the maximum motion footprint. */
object TextSafeArea {
    fun place(width:Float,height:Float,boxes:List<TextBoxRequest>):List<TextPlacedBox>? {
        require(width.isFinite() && height.isFinite() && width>0 && height>0)
        require(boxes.all { it.height.isFinite() && it.height>0 })
        require(boxes.map { it.id }.distinct().size==boxes.size)
        if(boxes.isEmpty()) return emptyList()
        val top=height*.10f; val bottom=height*.85f
        val gap=minOf(width*.012f,height*.01f)
        val ordered=boxes.sortedBy { it.position.ordinal }
        val total=ordered.sumOf { it.height.toDouble() }.toFloat()+gap*(ordered.size-1)
        if(total>bottom-top) return null
        var minimum=top
        var remaining=total
        return ordered.map { box ->
            val maximum=(bottom-remaining).coerceAtLeast(minimum)
            val preferred=when(box.position) {
                TextPosition.TOP -> top
                TextPosition.CENTER -> (top+bottom-box.height)/2
                TextPosition.BOTTOM -> bottom-box.height
            }
            val y=preferred.coerceIn(minimum,maximum)
            minimum=y+box.height+gap
            remaining-=box.height+gap
            TextPlacedBox(box.id,width*.08f,y,width*.92f,y+box.height)
        }
    }
}

object TextMotion {
    fun windowUs(layer:TextLayer)=minOf(200_000L,((layer.endUs-layer.startUs)/2).coerceAtLeast(1))
    fun entrance(layer:TextLayer,timeUs:Long)=((timeUs-layer.startUs).toDouble()/windowUs(layer)).toFloat().coerceIn(0f,1f)
    fun exit(layer:TextLayer,timeUs:Long)=((layer.endUs-timeUs).toDouble()/windowUs(layer)).toFloat().coerceIn(0f,1f)
    fun scale(layer:TextLayer,timeUs:Long)=.94f+.06f*entrance(layer,timeUs)
}
