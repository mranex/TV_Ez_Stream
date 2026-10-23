package vn.aquastream.tv

/** Only enable the adapter mode validated on this older Amlogic device. */
object SeekTuning {
    fun useAsyncCodec(sdk:Int,device:String)=sdk in 23..30 && device.equals("aquaman",ignoreCase=true)
}
