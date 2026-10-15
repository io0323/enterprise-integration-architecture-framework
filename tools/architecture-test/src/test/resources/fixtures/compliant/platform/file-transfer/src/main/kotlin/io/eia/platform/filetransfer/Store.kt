package io.eia.platform.filetransfer

import net.schmizz.sshj.SSHClient
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.hadoop.ParquetWriter
import software.amazon.awssdk.services.s3.S3Client

class Store(
    val client: S3Client,
    val writer: ParquetWriter<Any>,
    val conf: Configuration?,
    val ssh: SSHClient,
)
