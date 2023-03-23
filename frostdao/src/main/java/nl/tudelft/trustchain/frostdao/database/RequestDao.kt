package nl.tudelft.trustchain.frostdao.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RequestDao {

    @Query("Select * from request limit 1;")
    fun getWithRequestId() : Request

    @Insert
    fun insertRequest(requests: Request)

    @Query("select * from request where unix_time > :afterUnixTime")
    fun getAllAfterTime(afterUnixTime: Int): List<Request>

    @Query("select * from request where unix_time > :afterUnixTime and not done")
    fun getNotDoneAndReceivedAfterTime(afterUnixTime: Int): List<Request>
}
