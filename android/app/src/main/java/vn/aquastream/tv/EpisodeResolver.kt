package vn.aquastream.tv

import java.util.Locale

data class EpisodeFile(val id: String, val name: String, val offset: Int = 0)
data class EpisodeKey(val series: String, val season: Int, val episode: Int)

/** Conservative same-folder inference. No paths, tokens or video contents are parsed. */
object EpisodeResolver {
    private fun series(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
    fun key(filename: String): EpisodeKey? {
        val stem=filename.substringBeforeLast('.').replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\((?:[^)]*(?:\\d{3,4}p|hevc|avc|aac|x26[45]|web|bluray)[^)]*)\\)", RegexOption.IGNORE_CASE), " ").trim()
        Regex("(?i)^(.*?)s(\\d{1,2})[ ._-]*e(\\d{1,4})(?:\\D.*)?$").matchEntire(stem)?.let {
            return EpisodeKey(series(it.groupValues[1]).ifEmpty { "explicit-season" },it.groupValues[2].toInt(),it.groupValues[3].toInt())
        }
        Regex("(?i)^(.*?)(?:episode|ep|tập|tap)[ ._-]*(\\d{1,4})(?:v\\d+)?(?:\\D.*)?$").matchEntire(stem)?.let {
            return EpisodeKey(series(it.groupValues[1]).ifEmpty { "explicit-episode" },0,it.groupValues[2].toInt())
        }
        Regex("^(.*?)[ ._-]+(\\d{1,4})(?:v\\d+)?[ ._-]*$").matchEntire(stem)?.let {
            val prefix=series(it.groupValues[1]); val number=it.groupValues[2].toInt()
            if(prefix.any{c->c.isLetter()} && number !in 1900..2099 && number !in listOf(360,480,720,1080,2160,4320)) return EpisodeKey(prefix,0,number)
        }
        Regex("^(\\d{1,4})[ ._-]+(.+)$").matchEntire(stem)?.let {
            val prefix=series(it.groupValues[2]); val number=it.groupValues[1].toInt()
            if(prefix.any{c->c.isLetter()} && number !in 1900..2099) return EpisodeKey(prefix,0,number)
        }
        return null
    }
    fun compareNames(a: String, b: String): Int {
        val parts=Regex("\\d+|\\D+")
        val aa=parts.findAll(a.lowercase(Locale.ROOT)).map{it.value}.toList()
        val bb=parts.findAll(b.lowercase(Locale.ROOT)).map{it.value}.toList()
        for(i in 0 until minOf(aa.size,bb.size)) {
            val x=aa[i]; val y=bb[i]
            val c=if(x[0].isDigit() && y[0].isDigit()) {
                val xx=x.trimStart('0').ifEmpty{"0"}; val yy=y.trimStart('0').ifEmpty{"0"}
                xx.length.compareTo(yy.length).takeIf{it!=0} ?: xx.compareTo(yy)
            } else x.compareTo(y)
            if(c!=0) return c
        }
        return aa.size.compareTo(bb.size)
    }
    fun next(current: String, files: Iterable<EpisodeFile>): EpisodeFile? {
        val from=key(current) ?: return null
        return files.filter {
            val candidate=key(it.name)
            candidate!=null && candidate.series==from.series &&
                ((candidate.season==from.season && candidate.episode>from.episode) ||
                 (from.season>0 && candidate.season==from.season+1 && candidate.episode==1))
        }.minWithOrNull(compareBy<EpisodeFile>({key(it.name)!!.season},{key(it.name)!!.episode}).thenComparator { a,b -> compareNames(a.name,b.name) })
    }
}

object PlaybackMath {
    fun remaining(duration: Long, position: Long, speed: Float): Long? =
        if(duration<0 || !speed.isFinite() || speed<=0) null else ((duration-position).coerceAtLeast(0)/speed.toDouble()).toLong()
    fun speedLabel(hundredths: Int) = String.format(Locale.ROOT,"%.2f×",hundredths/100.0)
    fun parseSpeed(value: String): Int? = value.replace(',','.').toBigDecimalOrNull()?.let {
        if(it.scale()>2 || it<java.math.BigDecimal("0.25") || it>java.math.BigDecimal("4.00")) null else it.movePointRight(2).toInt()
    }
}
