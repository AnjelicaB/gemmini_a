
package gemmini

import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.tile.RoCCCommand
import org.chipsalliance.cde.config.Parameters
import GemminiISA._
import LocalAddr._
import Util._

// LdA

class VegaLoopMatmulLdAReq(val block_size: Int, val coreMaxAddrBits: Int, val iterator_bitwidth: Int, val max_addr: Int, val concurrent_loops: Int) extends Bundle {
  val max_i = UInt(iterator_bitwidth.W)
  val max_k = UInt(iterator_bitwidth.W)
  val pad_i = UInt(log2Up(block_size).W)
  val pad_k = UInt(log2Up(block_size).W)
  val dram_addr = UInt(coreMaxAddrBits.W)
  val dram_stride = UInt(coreMaxAddrBits.W)
  //val transpose = Bool()
  val addr_start = UInt(log2Up(max_addr).W)
  val loop_id = UInt(log2Up(concurrent_loops).W)
}

class VegaLoopMatmulLdA(block_size: Int, coreMaxAddrBits: Int, iterator_bitwidth: Int, max_addr: Int, input_w: Int,
                    max_block_len: Int, concurrent_loops: Int, mvin_rs2_t: MvinRs2)
                   (implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new VegaLoopMatmulLdAReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_addr, concurrent_loops)))
    val cmd = Decoupled(Output(new RoCCCommand))
    val i = Output(UInt(iterator_bitwidth.W))
    val k = Output(UInt(iterator_bitwidth.W))
    val idle = Output(Bool())
    val rob_overloaded = Input(Bool())
    val loop_id = Output(UInt(log2Up(concurrent_loops).W))
  })

  object State extends ChiselEnum {
    val idle, ld = Value
  }
  import State._
  val state = RegInit(idle)

  val req = Reg(new VegaLoopMatmulLdAReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_addr, concurrent_loops))

  val i = Reg(UInt(iterator_bitwidth.W))
  val k = Reg(UInt(iterator_bitwidth.W))
  /*
  val row_iterator = Mux(req.transpose, k, i)
  val col_iterator = Mux(req.transpose, i, k)

  val max_row_iterator = Mux(req.transpose, req.max_k, req.max_i)
  val max_col_iterator = Mux(req.transpose, req.max_i, req.max_k)

  val row_pad = Mux(req.transpose, req.pad_k, req.pad_i)
  val col_pad = Mux(req.transpose, req.pad_i, req.pad_k)
 */

  val row_iterator = i
  val col_iterator = k

  val max_row_iterator = req.max_i
  val max_col_iterator = req.max_k

  val row_pad = req.pad_i
  val col_pad = req.pad_k

  val max_col_dim = req.max_k
  val max_blocks = Mux(max_col_dim <= max_block_len.U, max_col_dim, max_block_len.U)

  val sp_addr_start = req.addr_start

  val dram_offset = (row_iterator * req.dram_stride + col_iterator) * block_size.U * (input_w/8).U
  val dram_addr = req.dram_addr + VegaLoopMatmul.castDramOffset(dram_offset)
  val sp_addr = sp_addr_start + (row_iterator * max_col_iterator + col_iterator) * block_size.U
  val blocks = Mux(col_iterator + max_blocks <= max_col_iterator, max_blocks, max_col_iterator-col_iterator)
  val cols = (blocks * block_size.U) - Mux(col_iterator + blocks >= max_col_iterator, col_pad, 0.U)
  val rows = block_size.U - Mux(row_iterator === max_row_iterator-1.U, row_pad, 0.U)
  dontTouch(dram_addr)
  dontTouch(sp_addr)
  dontTouch(rows)
  dontTouch(cols)

  val mvin_cmd = Wire(new RoCCCommand)
  mvin_cmd := DontCare
  mvin_cmd.inst.funct := LOAD_CMD
  mvin_cmd.rs1 := dram_addr

  val mvin_cmd_rs2 = Wire(mvin_rs2_t.cloneType)
  mvin_cmd_rs2 := DontCare
  mvin_cmd_rs2.num_rows := rows.asUInt
  mvin_cmd_rs2.num_cols := cols.asUInt
  mvin_cmd_rs2.local_addr := cast_to_sp_addr(mvin_cmd_rs2.local_addr, sp_addr)
  mvin_cmd.rs2 := mvin_cmd_rs2.asUInt
  //when(req.is_resadd){
  //  mvin_cmd_rs2.local_addr := cast_to_acc_addr(mvin_cmd_rs2.local_addr, sp_addr, accumulate = false.B, read_full = false.B)
  //}

  io.req.ready := state === idle
  io.i := i
  io.k := k
  io.idle := state === idle

  io.cmd.valid := state =/= idle && !io.rob_overloaded && req.dram_addr =/= 0.U
  io.cmd.bits := mvin_cmd

  io.loop_id := req.loop_id

  when(req.dram_addr === 0.U){
    state := idle
  }.elsewhen(io.cmd.fire) {
    // The order here is k, j, i
    //val i_blocks = Mux(req.transpose, max_blocks, 1.U)
    //val k_blocks = Mux(req.transpose, 1.U, max_blocks)
    val i_blocks = 1.U
    val k_blocks = max_blocks

    val next_i = floorAdd(i, i_blocks, req.max_i)
    val next_k = floorAdd(k, k_blocks, req.max_k, next_i === 0.U)

    i := next_i
    k := next_k

    when (next_i === 0.U && next_k === 0.U) {
      state := idle
    }
  }

  when (io.req.fire) {
    req := io.req.bits
    state := ld
    i := 0.U
    k := 0.U
  }
}

// LdB

class VegaLoopMatmulLdBReq(val block_size: Int, val coreMaxAddrBits: Int, val iterator_bitwidth: Int, val max_addr: Int, val concurrent_loops: Int) extends Bundle {
  val max_k = UInt(iterator_bitwidth.W)
  //val max_j = UInt(iterator_bitwidth.W)
  val pad_k = UInt(log2Up(block_size).W)
  //val pad_j = UInt(log2Up(block_size).W)
  val dram_addr = UInt(coreMaxAddrBits.W)
  //val dram_stride = UInt(coreMaxAddrBits.W)
  //val transpose = Bool()
  val addr_end = UInt(log2Up(max_addr+1).W)
  val loop_id = UInt(log2Up(concurrent_loops).W)
  val is_scale = Bool()
}

class VegaLoopMatmulLdB(block_size: Int, coreMaxAddrBits: Int, iterator_bitwidth: Int, max_addr: Int, input_w: Int,
                    max_block_len: Int, concurrent_loops: Int, mvin_rs2_t: MvinRs2)
                   (implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new VegaLoopMatmulLdBReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_addr, concurrent_loops)))
    val cmd = Decoupled(Output(new RoCCCommand))

    val k = Output(UInt(iterator_bitwidth.W))
    //val j = Output(UInt(iterator_bitwidth.W))

    val idle = Output(Bool())
    val rob_overloaded = Input(Bool())

    val loop_id = Output(UInt(log2Up(concurrent_loops).W))
  })

  object State extends ChiselEnum {
    val idle, ld = Value
  }
  import State._
  val state = RegInit(idle)

  val req = Reg(new VegaLoopMatmulLdBReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_addr, concurrent_loops))

  val k = Reg(UInt(iterator_bitwidth.W))
  //val j = Reg(UInt(iterator_bitwidth.W))

  /*
  val row_iterator = Mux(req.transpose, j, k)
  val col_iterator = Mux(req.transpose, k, j)

  val max_row_iterator = Mux(req.transpose, req.max_j, req.max_k)
  val max_col_iterator = Mux(req.transpose, req.max_k, req.max_j)

  val row_pad = Mux(req.transpose, req.pad_j, req.pad_k)
  val col_pad = Mux(req.transpose, req.pad_k, req.pad_j)

  val max_col_dim = Mux(req.transpose, req.max_k, req.max_j)
   */
  //val row_iterator = Mux(req.transpose, j, k)
  val col_iterator = k

  //val max_row_iterator = Mux(req.transpose, req.max_j, req.max_k)
  val max_col_iterator = req.max_k

  //val row_pad = Mux(req.transpose, req.pad_j, req.pad_k)
  val col_pad = req.pad_k

  val max_col_dim = req.max_k
  val max_blocks = Mux(max_col_dim <= max_block_len.U, max_col_dim, max_block_len.U)

  val sp_addr_start = Mux(req.is_scale, req.addr_end, req.addr_end - req.max_k)
  //val sp_addr_start = req.addr_end - req.max_k

  //val dram_offset = (row_iterator * req.dram_stride + col_iterator) * block_size.U * (input_w/8).U
  val dram_offset = col_iterator * block_size.U * (input_w/8).U
  val dram_addr = req.dram_addr + VegaLoopMatmul.castDramOffset(dram_offset)
  //val sp_addr = sp_addr_start + (row_iterator * max_col_iterator + col_iterator) * block_size.U
  val sp_addr = sp_addr_start + col_iterator
  val blocks = Mux(col_iterator + max_blocks <= max_col_iterator, max_blocks, max_col_iterator-col_iterator)
  val cols = (blocks * block_size.U) - Mux(col_iterator + blocks >= max_col_iterator, col_pad, 0.U)
  val rows = 1.U //block_size.U - Mux(max_row_iterator === max_row_iterator-1.U, row_pad, 0.U)
  dontTouch(dram_addr)
  dontTouch(sp_addr)
  dontTouch(cols)

  val mvin_cmd = Wire(new RoCCCommand)
  mvin_cmd := DontCare
  mvin_cmd.inst.funct := LOAD2_CMD
  mvin_cmd.rs1 := dram_addr

  val mvin_cmd_rs2 = Wire(mvin_rs2_t.cloneType)
  mvin_cmd_rs2 := DontCare
  mvin_cmd_rs2.num_rows := rows.asUInt
  mvin_cmd_rs2.num_cols := cols.asUInt
  mvin_cmd_rs2.local_addr := cast_to_sp_addr(mvin_cmd_rs2.local_addr, sp_addr)
  mvin_cmd.rs2 := mvin_cmd_rs2.asUInt

  when (req.is_scale){
    mvin_cmd_rs2.local_addr := cast_to_acc_addr(mvin_cmd_rs2.local_addr, sp_addr, accumulate = false.B, read_full = false.B)
  }

  io.req.ready := state === idle
  io.k := k
  //io.j := j
  io.idle := state === idle

  io.cmd.valid := state =/= idle && !io.rob_overloaded && req.dram_addr =/= 0.U
  io.cmd.bits := mvin_cmd

  io.loop_id := req.loop_id

  when(req.dram_addr === 0.U){
    state := idle
  }.elsewhen(io.cmd.fire) {
    // The order here is k, j, i
    //val j_blocks = Mux(req.transpose, 1.U, max_blocks)
    //val k_blocks = Mux(req.transpose, max_blocks, 1.U)
    val k_blocks = max_blocks

    //val next_j = floorAdd(j, j_blocks, req.max_j)
    //val next_k = floorAdd(k, k_blocks, req.max_k, next_j === 0.U)
    val next_k = floorAdd(k, k_blocks, req.max_k)

    //j := next_j
    k := next_k

    //when (next_j === 0.U && next_k === 0.U) {
    when(next_k === 0.U){
      state := idle
    }
  }

  when (io.req.fire) {
    req := io.req.bits
    state := ld
    //j := 0.U
    k := 0.U
  }
}

// LdD

class VegaLoopMatmulLdDReq(val block_size: Int, val coreMaxAddrBits: Int, val iterator_bitwidth: Int, val max_acc_addr: Int, val concurrent_loops: Int) extends Bundle {
  //val max_j = UInt(iterator_bitwidth.W)
  val max_i = UInt(iterator_bitwidth.W)
  //val pad_j = UInt(log2Up(block_size).W)
  val pad_i = UInt(log2Up(block_size).W)
  val dram_addr = UInt(coreMaxAddrBits.W)
  //val dram_stride = UInt(coreMaxAddrBits.W)
  val low_d = Bool()
  val addr_start = UInt(log2Up(max_acc_addr).W)
  val loop_id = UInt(log2Up(concurrent_loops).W)
}

class VegaLoopMatmulLdD(block_size: Int, coreMaxAddrBits: Int, iterator_bitwidth: Int, max_acc_addr: Int, input_w: Int,
                    acc_w: Int, max_block_len: Int, max_block_len_acc: Int, concurrent_loops: Int, mvin_rs2_t: MvinRs2)
                   (implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new VegaLoopMatmulLdDReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_acc_addr, concurrent_loops)))
    val cmd = Decoupled(Output(new RoCCCommand))

    val idle = Output(Bool())
    val rob_overloaded = Input(Bool())

    val loop_id = Output(UInt(log2Up(concurrent_loops).W))
  })

  object State extends ChiselEnum {
    val idle, ld = Value
  }
  import State._
  val state = RegInit(idle)

  val req = Reg(new VegaLoopMatmulLdDReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_acc_addr, concurrent_loops))

  //val max_blocks = Mux(req.low_d, Mux(req.max_j <= max_block_len.U, req.max_j, max_block_len.U),
  //  Mux(req.max_j <= max_block_len_acc.U, req.max_j, max_block_len_acc.U))
  val max_blocks = 1.U

  //val j = Reg(UInt(iterator_bitwidth.W))
  val i = Reg(UInt(iterator_bitwidth.W))

  val acc_addr_start = req.addr_start

  //val dram_offset = Mux(req.low_d, (i * req.dram_stride + j) * block_size.U * (input_w/8).U,
  //  (i * req.dram_stride + j) * block_size.U * (acc_w/8).U)
  val dram_offset = Mux(req.low_d, i * block_size.U * (input_w/8).U,
    i * block_size.U * (acc_w/8).U)
  val dram_addr = req.dram_addr + VegaLoopMatmul.castDramOffset(dram_offset)
  //val sp_addr = acc_addr_start + (i * req.max_j + j) * block_size.U
  val sp_addr = acc_addr_start + i
  //val blocks = Mux(j + max_blocks <= req.max_j, max_blocks, req.max_j-j)
  val blocks = 1.U //Mux(i + max_blocks <= req.max_i, max_blocks, req.max_i-i)
  //val cols = (blocks * block_size.U) - Mux(j + blocks >= req.max_j, req.pad_j, 0.U)
  val cols = block_size.U - Mux(i + blocks >= req.max_i, req.pad_i, 0.U)
  //val rows = block_size.U - Mux(i === req.max_i-1.U, req.pad_i, 0.U)
  val rows = 1.U

  val mvin_cmd = Wire(new RoCCCommand)
  mvin_cmd := DontCare
  mvin_cmd.inst.funct := LOAD3_CMD
  mvin_cmd.rs1 := dram_addr

  val mvin_cmd_rs2 = Wire(mvin_rs2_t.cloneType)
  mvin_cmd_rs2 := DontCare
  mvin_cmd_rs2.num_rows := rows.asUInt
  mvin_cmd_rs2.num_cols := cols.asUInt
  mvin_cmd_rs2.local_addr := cast_to_acc_addr(mvin_cmd_rs2.local_addr, sp_addr, accumulate = false.B, read_full = false.B)
  mvin_cmd.rs2 := mvin_cmd_rs2.asUInt

  io.req.ready := state === idle
  io.idle := state === idle

  // The order here is k, j, i
  io.cmd.valid := state =/= idle && !io.rob_overloaded && req.dram_addr =/= 0.U
  io.cmd.bits := mvin_cmd

  io.loop_id := req.loop_id

  when (req.dram_addr === 0.U) {
    state := idle
  }.elsewhen (io.cmd.fire) {
    // The order here is k, j, i
    val next_i = floorAdd(i, 1.U, req.max_i)
    //val next_j = floorAdd(j, max_blocks, req.max_j, next_i === 0.U)

    i := next_i
    //j := next_j

    when (next_i === 0.U) {
      state := idle
    }
  }

  when (io.req.fire) {
    req := io.req.bits
    state := ld
    //j := 0.U
    i := 0.U
  }
}

// Compute
class VegaLoopMatmulExecuteReq(val block_size: Int, val coreMaxAddrBits: Int, val iterator_bitwidth: Int, val max_addr: Int, val max_acc_addr: Int, val concurrent_loops: Int) extends Bundle {
  //val max_j = UInt(iterator_bitwidth.W)
  val max_k = UInt(iterator_bitwidth.W)
  val max_i = UInt(iterator_bitwidth.W)
  //val pad_j = UInt(log2Up(block_size).W)
  val pad_k = UInt(log2Up(block_size).W)
  val pad_i = UInt(log2Up(block_size).W)
  //val a_tranpose = Bool()
  //val b_tranpose = Bool()
  val accumulate = Bool()
  val a_addr_start = UInt(log2Up(max_addr).W)
  val b_addr_end = UInt(log2Up(max_addr+1).W)
  val c_addr_start = UInt(log2Up(max_acc_addr).W)
  val loop_id = UInt(log2Up(concurrent_loops).W)
  val skip = Bool()
}

class VegaLoopMatmulExecute(block_size: Int, coreMaxAddrBits: Int, iterator_bitwidth: Int, max_addr: Int, max_acc_addr: Int, concurrent_loops: Int,
                        preload_rs1_t: PreloadRs, preload_rs2_t: PreloadRs,
                        compute_rs1_t: ComputeRs, compute_rs2_t: ComputeRs)
                       (implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new VegaLoopMatmulExecuteReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_addr, max_acc_addr, concurrent_loops)))
    val cmd = Decoupled(Output(new RoCCCommand))

    val k = Output(UInt(iterator_bitwidth.W))
    //val j = Output(UInt(iterator_bitwidth.W))
    val i = Output(UInt(iterator_bitwidth.W))

    val ld_ka = Input(UInt(iterator_bitwidth.W))
    val ld_kb = Input(UInt(iterator_bitwidth.W))
    //val ld_j = Input(UInt(iterator_bitwidth.W))
    val ld_i = Input(UInt(iterator_bitwidth.W))
    val lda_completed = Input(Bool())
    val ldb_completed = Input(Bool())
    val ldd_completed = Input(Bool())

    val idle = Output(Bool())
    val rob_overloaded = Input(Bool())

    val loop_id = Output(UInt(log2Up(concurrent_loops).W))
  })

  object State extends ChiselEnum {
    val idle, pre, comp = Value
  }
  import State._
  val state = RegInit(idle)

  val req = Reg(new VegaLoopMatmulExecuteReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_addr, max_acc_addr, concurrent_loops))

  val c_addr_start = /*(BigInt(1) << 31).U |*/ req.c_addr_start
  val b_addr_start = req.b_addr_end - req.max_k //* req.max_j * block_size.U

  val k = Reg(UInt(iterator_bitwidth.W))
  //val j = Reg(UInt(iterator_bitwidth.W))
  val i = Reg(UInt(iterator_bitwidth.W))
  /*
  val a_row = Mux(req.a_tranpose, k, i)
  val a_col = Mux(req.a_tranpose, i, k)
  val b_row = Mux(req.b_tranpose, j, k)
  val b_col = Mux(req.b_tranpose, k, j)

  val a_max_col = Mux(req.a_tranpose, req.max_i, req.max_k)
  val b_max_col = Mux(req.b_tranpose, req.max_k, req.max_j)
  */
  val a_row = i
  val a_col = k
  val b_row = k
  val b_col = 1.U

  //val a_max_col = Mux(req.a_tranpose, req.max_i, req.max_k)
  //val b_max_col = Mux(req.b_tranpose, req.max_k, req.max_j)

  val a_max_col = req.max_k
  //val b_max_col = Mux(req.b_tranpose, req.max_k, req.max_j)

  val a_addr = req.a_addr_start + (a_row * a_max_col + a_col) * block_size.U
  val b_addr = b_addr_start + b_row //(b_row * b_max_col + b_col) * block_size.U
  val c_addr = c_addr_start + a_row //(i * req.max_j + j) * block_size.U

  val a_cols = block_size.U - Mux(k === req.max_k - 1.U, req.pad_k, 0.U)
  val a_rows = block_size.U - Mux(i === req.max_i - 1.U, req.pad_i, 0.U)
  val b_cols = 1//block_size.U - Mux(j === req.max_j - 1.U, req.pad_j, 0.U)
  val b_rows = block_size.U - Mux(k === req.max_k - 1.U, req.pad_k, 0.U)
  val c_cols = 1//block_size.U - Mux(j === req.max_j - 1.U, req.pad_j, 0.U)
  val c_rows = block_size.U - Mux(i === req.max_i - 1.U, req.pad_i, 0.U)
  //dontTouch(b_addr)
  //dontTouch(c_addr)
  //dontTouch(b_rows)
  val pre_cmd = Wire(new RoCCCommand)
  pre_cmd := DontCare
  pre_cmd.inst.funct := PRELOAD_CMD

  val pre_cmd_rs1 = Wire(preload_rs1_t.cloneType)
  pre_cmd_rs1 := DontCare
  pre_cmd_rs1.num_rows := b_rows.asUInt
  pre_cmd_rs1.num_cols := b_cols.asUInt
  pre_cmd_rs1.local_addr := Mux(i === 0.U, cast_to_sp_addr(pre_cmd_rs1.local_addr, b_addr),
    garbage_addr(pre_cmd_rs1.local_addr))

  val pre_cmd_rs2 = Wire(preload_rs2_t.cloneType)
  pre_cmd_rs2 := DontCare
  pre_cmd_rs2.num_rows := c_rows.asUInt
  pre_cmd_rs2.num_cols := c_cols.asUInt
  pre_cmd_rs2.local_addr := cast_to_acc_addr(pre_cmd_rs2.local_addr, c_addr, accumulate = req.accumulate || k =/= 0.U, read_full = false.B)

  pre_cmd.rs1 := pre_cmd_rs1.asUInt
  pre_cmd.rs2 := pre_cmd_rs2.asUInt

  val comp_cmd = Wire(new RoCCCommand())
  comp_cmd := DontCare
  comp_cmd.inst.funct := Mux(i === 0.U, COMPUTE_AND_FLIP_CMD, COMPUTE_AND_STAY_CMD)

  val comp_cmd_rs1 = Wire(compute_rs1_t.cloneType)
  comp_cmd_rs1 := DontCare
  comp_cmd_rs1.num_rows := a_rows.asUInt
  comp_cmd_rs1.num_cols := a_cols.asUInt
  comp_cmd_rs1.local_addr := cast_to_sp_addr(comp_cmd_rs1.local_addr, a_addr)

  val comp_cmd_rs2 = Wire(compute_rs2_t.cloneType)
  comp_cmd_rs2 := DontCare
  comp_cmd_rs2.num_rows := block_size.U
  comp_cmd_rs2.num_cols := block_size.U
  comp_cmd_rs2.local_addr := garbage_addr(comp_cmd_rs2.local_addr)

  comp_cmd.rs1 := comp_cmd_rs1.asUInt
  comp_cmd.rs2 := comp_cmd_rs2.asUInt

  io.req.ready := state === idle
  io.k := k
  //io.j := j
  io.i := i
  io.idle := state === idle

  // The order here is k, j, i
  val lda_ahead = io.lda_completed || io.ld_ka > k || (io.ld_ka === k && io.ld_i > i)
  val ldb_ahead = io.ldb_completed || io.ld_kb > k //|| (io.ld_ka === k)// && io.ld_j > j)
  val ldd_ahead = io.ldd_completed
  val ld_ahead = lda_ahead && ldb_ahead && ldd_ahead

  io.cmd.valid := state =/= idle && !io.rob_overloaded && ld_ahead && !req.skip
  io.cmd.bits := Mux(state === pre, pre_cmd, comp_cmd)

  io.loop_id := req.loop_id

  when(req.skip){
    state := idle
  }.elsewhen (io.cmd.fire) {
    when (state === pre) {
      state := comp
    }.otherwise {
      val next_i = floorAdd(i, 1.U, req.max_i)
      //val next_j = floorAdd(j, 1.U, req.max_j, next_i === 0.U)
      val next_k = floorAdd(k, 1.U, req.max_k, next_i === 0.U)

      k := next_k
      //j := next_j
      i := next_i

      state := Mux(next_k === 0.U && next_i === 0.U, idle, pre)
    }
  }

  when (io.req.fire) {
    req := io.req.bits
    state := pre
    //j := 0.U
    k := 0.U
    i := 0.U
  }

  //assert(!(state =/= idle && req.a_tranpose && req.b_tranpose))
}

// StC

class VegaLoopMatmulStCReq(val block_size: Int, val coreMaxAddrBits: Int, val iterator_bitwidth: Int, val max_acc_addr: Int, val concurrent_loops: Int) extends Bundle {
  val max_k = UInt(iterator_bitwidth.W)
  //val max_j = UInt(iterator_bitwidth.W)
  val max_i = UInt(iterator_bitwidth.W)
  //val pad_j = UInt(log2Up(block_size).W)
  val pad_i = UInt(log2Up(block_size).W)
  val dram_addr = UInt(coreMaxAddrBits.W)
  //val dram_stride = UInt(coreMaxAddrBits.W)
  val full_c = Bool()
  val act = UInt(Activation.bitwidth.W)
  val addr_start = UInt(log2Up(max_acc_addr).W)
  val loop_id = UInt(log2Up(concurrent_loops).W)
  val is_scale = Bool()
}

class VegaLoopMatmulStC(block_size: Int, coreMaxAddrBits: Int, iterator_bitwidth: Int, max_acc_addr: Int, input_w: Int, acc_w: Int, max_block_len: Int, concurrent_loops: Int, mvout_rs2_t: MvoutRs2)
                   (implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new VegaLoopMatmulStCReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_acc_addr, concurrent_loops)))
    val cmd = Decoupled(Output(new RoCCCommand))

    val ex_k = Input(UInt(iterator_bitwidth.W))
    //val ex_j = Input(UInt(iterator_bitwidth.W))
    val ex_i = Input(UInt(iterator_bitwidth.W))
    val ex_completed = Input(Bool())

    //val j = Output(UInt(iterator_bitwidth.W))
    val i = Output(UInt(iterator_bitwidth.W))

    val idle = Output(Bool())
    val rob_overloaded = Input(Bool())

    val loop_id = Output(UInt(log2Up(concurrent_loops).W))
  })

  object State extends ChiselEnum {
    val idle, st = Value
  }
  import State._
  val state = RegInit(idle)

  val req = Reg(new VegaLoopMatmulStCReq(block_size, coreMaxAddrBits, iterator_bitwidth, max_acc_addr, concurrent_loops))

  val max_blocks = 1.U// Mux(req.full_c, 1.U, Mux(req.max_j <= max_block_len.U, req.max_j, max_block_len.U))

  // Non-normalization-related iterators and calculations
  //val j = Reg(UInt(iterator_bitwidth.W))
  val i = Reg(UInt(iterator_bitwidth.W))

  val acc_addr_start = /*(BigInt(1) << 31).U | (req.full_c << 29.U).asUInt |*/ req.addr_start

  //val dram_offset = Mux(req.full_c, (i * req.dram_stride + j) * block_size.U * (acc_w/8).U,
  //  (i * req.dram_stride + j) * block_size.U * (input_w/8).U)
  val dram_offset = i * block_size.U * Mux(req.full_c, (acc_w/8).U, (input_w/8).U)
  val dram_addr = req.dram_addr + VegaLoopMatmul.castDramOffset(dram_offset)
  //val sp_addr = acc_addr_start + (i * req.max_j + j) * block_size.U
  val sp_addr = acc_addr_start + i
  val blocks = 1.U //Mux(j + max_blocks <= req.max_j, max_blocks, req.max_j-j)
  val cols = WireInit(block_size.U) //(blocks * block_size.U) - Mux(j + blocks >= req.max_j, req.pad_j, 0.U)
  //val rows = block_size.U - Mux(i === req.max_i-1.U, req.pad_i, 0.U)
  val rows = WireInit(Mux(i + block_size.U > req.max_i, req.max_i - i, block_size.U))
  //val rows = WireInit(block_size.U)
  //when(req.is_scale){
  when(req.pad_i =/= 0.U && i + block_size.U >= req.max_i && req.is_scale){
    rows := 1.U
    cols := Mux(i === req.max_i - 1.U, block_size.U - req.pad_i, block_size.U)
  }

  dontTouch(dram_addr)
  dontTouch(sp_addr)
  dontTouch(rows)
  dontTouch(cols)

  val mvout_cmd = Wire(new RoCCCommand)
  mvout_cmd := DontCare
  mvout_cmd.inst.funct := STORE_CMD
  mvout_cmd.rs1 := dram_addr

  val mvout_cmd_rs2 = Wire(mvout_rs2_t.cloneType)
  mvout_cmd_rs2 := DontCare
  mvout_cmd_rs2.num_rows := rows.asUInt
  mvout_cmd_rs2.num_cols := cols.asUInt
  mvout_cmd_rs2.local_addr := cast_to_acc_addr(mvout_cmd_rs2.local_addr, sp_addr, accumulate = false.B, read_full = req.full_c)
  /*
  when(req.is_scale){
    mvout_cmd_rs2.local_addr := cast_to_sp_addr(mvout_cmd_rs2.local_addr, sp_addr)
  }

   */
  mvout_cmd.rs2 := mvout_cmd_rs2.asUInt

  io.req.ready := state === idle
  //io.j := j
  io.i := i
  io.idle := state === idle

  /*
  // The order here is k, j, i when not doing LAYERNORM or SOFTMAX
  val ex_ahead = WireInit(io.ex_completed ||
    ((req.act =/= Activation.LAYERNORM) && (req.act =/= Activation.SOFTMAX) &&
      (io.ex_k === req.max_k - 1.U &&
        (io.ex_j >= j + blocks ||
          ((io.ex_j === j + blocks - 1.U) && io.ex_i > i)))))
  when(req.is_resadd){
    ex_ahead := io.ex_completed || (io.ex_i > i || (io.ex_i === i && io.ex_j >= j + blocks))
  }

   */
  // The order here is k, j, i when not doing LAYERNORM or SOFTMAX
  val ex_ahead = WireInit(io.ex_completed ||
    (io.ex_k === req.max_k - 1.U &&
      (io.ex_i >= i + block_size.U)))
      //(io.ex_i > i)))
  when(req.is_scale){
    ex_ahead := io.ex_completed || (io.ex_i >= i + block_size.U)
  }
  io.cmd.valid := state =/= idle && !io.rob_overloaded && ex_ahead && req.dram_addr =/= 0.U
  io.cmd.bits := mvout_cmd

  io.loop_id := req.loop_id

  when (req.dram_addr === 0.U) {
    state := idle
  }.elsewhen (io.cmd.fire() && state === st) {
    // The order here is k, j, i
    val next_i_size = Mux(i + block_size.U >= req.max_i && req.pad_i =/= 0.U && req.is_scale, 1.U, block_size.U)
    //val next_i = floorAdd(i, 1.U, req.max_i)
    val next_i = floorAdd(i, next_i_size, req.max_i)
    //val next_j = floorAdd(j, max_blocks, req.max_j, next_i === 0.U)

    i := next_i
    //j := next_j

    when (next_i === 0.U) {
      state := idle
    }
  }
    /*
    .elsewhen (io.cmd.fire() && state === ln_config) {
    state := ln_st
  }.elsewhen (io.cmd.fire() && state === ln_st) {
    val next_j = floorAdd(j, max_blocks, req.max_j)
    val next_stat_id = floorAdd(ln_stat_id, 1.U, ln_stat_ids, next_j === 0.U)
    val next_cmd = floorAdd(ln_cmd, 1.U, ln_norm_cmds.size.U, next_j === 0.U && next_stat_id === 0.U)
    val next_row = floorAdd(ln_row, NORM_STAT_IDS.U, rows, next_j === 0.U && next_stat_id === 0.U && next_cmd === 0.U)
    val next_i = floorAdd(i, 1.U, req.max_i,
      next_j === 0.U && next_stat_id === 0.U && next_cmd === 0.U && next_row === 0.U)

    j := next_j
    ln_stat_id := next_stat_id
    ln_cmd := next_cmd
    ln_row := next_row
    i := next_i

    when (next_i === 0.U && next_row === 0.U && next_cmd === 0.U && next_stat_id === 0.U && next_j === 0.U) {
      state := idle
    }.elsewhen (next_j === 0.U) {
      state := ln_config
    }
  }
     */

  when (io.req.fire) {
    req := io.req.bits
    //state := Mux((io.req.bits.act === Activation.LAYERNORM) || (io.req.bits.act === Activation.SOFTMAX), ln_config, st)
    state := st

    //j := 0.U
    i := 0.U
  }
}

// Combined loop
class VegaLoopMatmulState(val iterator_bitwidth: Int, val coreMaxAddrBits: Int, val max_addr: Int, val max_acc_addr: Int) extends Bundle {
  val max_k = UInt(iterator_bitwidth.W)
  //val max_j = UInt(iterator_bitwidth.W)
  val max_i = UInt(iterator_bitwidth.W)

  val pad_k = UInt(iterator_bitwidth.W)
  //val pad_j = UInt(iterator_bitwidth.W)
  val pad_i = UInt(iterator_bitwidth.W)

  val a_dram_addr = UInt(coreMaxAddrBits.W)
  val b_dram_addr = UInt(coreMaxAddrBits.W)
  val d_dram_addr = UInt(coreMaxAddrBits.W)
  val c_dram_addr = UInt(coreMaxAddrBits.W)

  val a_dram_stride = UInt(coreMaxAddrBits.W)
  //val b_dram_stride = UInt(coreMaxAddrBits.W)
  //val d_dram_stride = UInt(coreMaxAddrBits.W)
  //val c_dram_stride = UInt(coreMaxAddrBits.W)

  val a_transpose = Bool()
  val b_transpose = Bool()

  val act = UInt(Activation.bitwidth.W)

  val low_d = Bool()
  val full_c = Bool()
  val ex_accumulate = Bool()

  val a_ex_spad_id = UInt(2.W)
  val b_ex_spad_id = UInt(2.W)
  val configured = Bool()

  val running = Bool()

  val lda_started = Bool()
  val ldb_started = Bool()
  val ex_started = Bool()
  val ldd_started = Bool()
  val st_started = Bool()

  val lda_completed = Bool()
  val ldb_completed = Bool()
  val ex_completed = Bool()
  val ldd_completed = Bool()
  val st_completed = Bool()

  def all_completed(dummy: Int=0): Bool = lda_completed && ldb_completed && ldd_completed && ex_completed && st_completed

  val a_addr_start = UInt(log2Up(max_addr).W)
  val b_addr_end = UInt(log2Up(max_addr+1).W)
  val scale_addr_start = UInt(log2Up(max_acc_addr).W)

  def reset(): Unit = {
    configured := false.B

    running := false.B

    lda_started := false.B
    ldb_started := false.B
    ex_started := false.B
    ldd_started := false.B
    st_started := false.B

    lda_completed := false.B
    ldb_completed := false.B
    ex_completed := false.B
    ldd_completed := false.B
    st_completed := false.B

    //is_resadd := false.B
  }
}

class VegaLoopMatmul(block_size: Int, coreMaxAddrBits: Int, reservation_station_size: Int, max_lds: Int, max_exs: Int, max_sts: Int,
                 max_addr: Int, max_acc_addr: Int, input_w: Int, acc_w: Int, dma_max_bytes: Int, has_vega_gate: Boolean,
                 mvin_rs2_t: MvinRs2, preload_rs1_t: PreloadRs, preload_rs2_t: PreloadRs,
                 compute_rs1_t: ComputeRs, compute_rs2_t: ComputeRs, mvout_rs2_t: MvoutRs2)
                (implicit p: Parameters) extends Module {
  val iterator_bitwidth = 16
  val max_block_len = (dma_max_bytes / (block_size * input_w / 8)) max 1
  val max_block_len_acc = (dma_max_bytes / (block_size * acc_w / 8)) max 1

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new GemminiCmd(reservation_station_size)))
    val out = Decoupled(new GemminiCmd(reservation_station_size))
    val ld_completed = Input(UInt(log2Up(reservation_station_size+1).W))
    val st_completed = Input(UInt(log2Up(reservation_station_size+1).W))
    val ex_completed = Input(UInt(log2Up(reservation_station_size+1).W))
    val busy = Output(Bool())

    val clock_enable = Input(Bool())
  })

  // Create states
  val concurrent_loops = 2
  val loops = Reg(Vec(concurrent_loops, new VegaLoopMatmulState(iterator_bitwidth, coreMaxAddrBits, max_addr, max_acc_addr)))
  val head_loop_id = Reg(UInt(log2Up(concurrent_loops).W))
  val tail_loop_id = (~head_loop_id).asUInt // This is the loop that we always try to configure if available
  val head_loop = loops(head_loop_id)
  val tail_loop = loops(tail_loop_id)

  val loop_configured = loops.map(_.configured).reduce(_ || _)

  val loop_being_configured_id = Mux(head_loop.configured, tail_loop_id, head_loop_id)
  val loop_being_configured = loops(loop_being_configured_id)

  val is_scale = RegInit(false.B)

  val max_all_addr = if(max_addr > max_acc_addr) max_addr else max_acc_addr 
  // Create inner modules
  val ldA = Module(new VegaLoopMatmulLdA(block_size, coreMaxAddrBits, iterator_bitwidth, max_all_addr, input_w, max_block_len, concurrent_loops, mvin_rs2_t))
  val ldB = Module(new VegaLoopMatmulLdB(block_size, coreMaxAddrBits, iterator_bitwidth, max_all_addr, input_w, max_block_len, concurrent_loops, mvin_rs2_t))
  val ldD = Module(new VegaLoopMatmulLdD(block_size, coreMaxAddrBits, iterator_bitwidth, max_acc_addr, input_w, acc_w, max_block_len, max_block_len_acc, concurrent_loops, mvin_rs2_t))
  val ex = Module(new VegaLoopMatmulExecute(block_size, coreMaxAddrBits, iterator_bitwidth, max_addr, max_acc_addr, concurrent_loops, preload_rs1_t, preload_rs2_t, compute_rs1_t, compute_rs2_t))
  val stC = Module(new VegaLoopMatmulStC(block_size, coreMaxAddrBits, iterator_bitwidth, max_acc_addr, input_w, acc_w, max_block_len, concurrent_loops, mvout_rs2_t))

  // Create command queue
  val cmd = Queue(io.in)

  io.busy := cmd.valid || loop_configured

  // Create ld arbiters
  val ldab_arb = Module(new VegaWeightedArbiter(new RoCCCommand(), maxWeightA=255, staticWeightAEnabled=true)) // TODO magic numbers
  ldab_arb.io.inA <> ldA.io.cmd
  ldab_arb.io.inB <> ldB.io.cmd
  val ab_loads_on_same_loop = ldA.io.loop_id === ldB.io.loop_id
  val forceA = !ab_loads_on_same_loop && ldA.io.loop_id === head_loop_id
  val forceB = !ab_loads_on_same_loop && ldB.io.loop_id === head_loop_id
  ldab_arb.io.forceA := Mux(is_scale, false.B, forceA) //Mux(is_resadd, ab_loads_on_same_loop && !ldA.io.idle, forceA)
  ldab_arb.io.forceB := Mux(is_scale, true.B, forceB) //Mux(is_resadd, forceB || ldA.io.idle, forceB)
  ldab_arb.io.weightA := 0.U
  ldab_arb.io.inA_idle := ldA.io.idle
  ldab_arb.io.inB_idle := ldB.io.idle
  ldab_arb.io.inA_k := ldA.io.k
  ldab_arb.io.inA_i := ldA.io.i
  ldab_arb.io.inB_k := ldB.io.k
  //ldab_arb.io.inB_j := ldB.io.j

  // Create global arbiter
  val arb = Module(new Arbiter(new RoCCCommand(), 4))
  arb.io.in(0) <> stC.io.cmd
  arb.io.in(1) <> ex.io.cmd
  arb.io.in(2) <> ldD.io.cmd
  arb.io.in(3) <> ldab_arb.io.out
  val unrolled_cmd = arb.io.out

  // Create reservation station utilization counters
  val ld_utilization = RegInit(0.U(log2Up(max_lds+1).W))
  val st_utilization = RegInit(0.U(log2Up(max_sts+1).W))
  val ex_utilization = RegInit(0.U(log2Up(max_exs+1).W))

  ld_utilization := ld_utilization +& (ldA.io.cmd.fire || ldB.io.cmd.fire || ldD.io.cmd.fire) -& io.ld_completed
  st_utilization := st_utilization +& stC.io.cmd.fire -& io.st_completed
  ex_utilization := ex_utilization +& ex.io.cmd.fire -& io.ex_completed

  assert(ld_utilization >= io.ld_completed, "ld utilization underflow")
  assert(st_utilization >= io.st_completed, "st utilization underflow")
  assert(ex_utilization >= io.ex_completed, "ex utilization underflow")

  // Wire up unrolled command output
  val is_loop_run_cmd = cmd.bits.cmd.inst.funct === LOOP_VEGA
  val is_loop_config_cmd = cmd.bits.cmd.inst.funct >= LOOP_VEGA_CONFIG_BOUNDS && cmd.bits.cmd.inst.funct <= LOOP_VEGA_CONFIG_ADDRS_DC
  val is_loop_cmd = is_loop_run_cmd || is_loop_config_cmd

  io.out.bits.cmd := Mux(loop_configured, unrolled_cmd.bits, cmd.bits.cmd)
  io.out.bits.cmd.status := cmd.bits.cmd.status // TODO This is not guaranteed to be the correct fix! We must fix this
  io.out.bits.rob_id := DontCare
  io.out.bits.from_matmul_fsm := Mux(loop_configured, true.B, cmd.bits.from_matmul_fsm)
  io.out.bits.from_conv_fsm := Mux(loop_configured, false.B, cmd.bits.from_conv_fsm)
  io.out.valid := Mux(loop_configured, unrolled_cmd.valid, cmd.valid && !is_loop_config_cmd && !is_loop_run_cmd)

  cmd.ready := Mux(is_loop_cmd, !loop_being_configured.configured, !loop_configured && io.out.ready)
  arb.io.out.ready := io.out.ready

  if(has_vega_gate) {
    // when clock is gated
    when(!io.clock_enable) {
      io.out <> io.in
      io.in.ready := io.out.ready
      //io.in.ready := true.B
    }
  }

  // Wire up overloaded signals
  ldA.io.rob_overloaded := ld_utilization >= max_lds.U
  ldB.io.rob_overloaded := ld_utilization >= max_lds.U
  ex.io.rob_overloaded := ex_utilization >= max_exs.U
  ldD.io.rob_overloaded := ld_utilization >= max_lds.U
  stC.io.rob_overloaded := st_utilization >= max_sts.U

  // Wire up iterator inputs
  ex.io.lda_completed := (ldA.io.loop_id =/= ex.io.loop_id) || ldA.io.idle
  ex.io.ldb_completed := (ldB.io.loop_id =/= ex.io.loop_id) || ldB.io.idle
  ex.io.ldd_completed := (ldD.io.loop_id =/= ex.io.loop_id) || ldD.io.idle
  ex.io.ld_ka := ldA.io.k
  ex.io.ld_kb := ldB.io.k
  //ex.io.ld_j := ldB.io.j
  ex.io.ld_i := ldA.io.i

  stC.io.ex_completed := (ex.io.loop_id =/= stC.io.loop_id) || ex.io.idle
  stC.io.ex_k := ex.io.k
  //stC.io.ex_j := ex.io.j
  stC.io.ex_i := ex.io.i

  // when loop matmul is used as resadd unroller
  // skip ex
  // track ldB instead of ex
  
  when(is_scale){
    stC.io.ex_completed := (ldB.io.loop_id =/= stC.io.loop_id || ldB.io.idle)// && (ldB.io.loop_id =/= stC.io.loop_id || ldB.io.idle)
    stC.io.ex_k := 0.U // req.max_k shall be 1
    //stC.io.ex_j := ldB.io.j
    stC.io.ex_i := ldB.io.k
    //ldB.io.rob_overloaded := ld_utilization >= max_lds.U || !((ldA.io.loop_id =/= ldB.io.loop_id) || ldA.io.idle)
  }
   

  val loops_configured = RegInit(0.U(16.W))
  //dontTouch(loops_configured)

  // Create config registers
  when(cmd.valid && is_loop_cmd && !loop_being_configured.configured) {

    switch (cmd.bits.cmd.inst.funct) {
      is (LOOP_VEGA_CONFIG_BOUNDS) {
        loop_being_configured.max_k := cmd.bits.cmd.rs2(iterator_bitwidth * 3 - 1, iterator_bitwidth * 2)
        //loop_being_configured.max_j := cmd.bits.cmd.rs2(iterator_bitwidth * 2 - 1, iterator_bitwidth)
        loop_being_configured.max_i := cmd.bits.cmd.rs2(iterator_bitwidth-1, 0)

        loop_being_configured.pad_k := cmd.bits.cmd.rs1(iterator_bitwidth * 3 - 1, iterator_bitwidth * 2)
        //loop_being_configured.pad_j := cmd.bits.cmd.rs1(iterator_bitwidth * 2 - 1, iterator_bitwidth)
        loop_being_configured.pad_i := cmd.bits.cmd.rs1(iterator_bitwidth-1, 0)
      }

      is (LOOP_VEGA_CONFIG_ADDRS_AB) {
        loop_being_configured.a_dram_addr := cmd.bits.cmd.rs1
        loop_being_configured.b_dram_addr := cmd.bits.cmd.rs2
      }

      is (LOOP_VEGA_CONFIG_ADDRS_DC) {
        loop_being_configured.d_dram_addr := cmd.bits.cmd.rs1
        loop_being_configured.c_dram_addr := cmd.bits.cmd.rs2
      }
/*
      is (LOOP_VEGA_CONFIG_STRIDES_AB) {
        loop_being_configured.a_dram_stride := cmd.bits.cmd.rs1
        //loop_being_configured.b_dram_stride := cmd.bits.cmd.rs2
      }

      is (LOOP_VEGA_CONFIG_STRIDES_DC) {
        loop_being_configured.d_dram_stride := cmd.bits.cmd.rs1
        loop_being_configured.c_dram_stride := cmd.bits.cmd.rs2
      }
*/
      is (LOOP_VEGA) {
        loop_being_configured.ex_accumulate := cmd.bits.cmd.rs1(0)
        loop_being_configured.full_c := cmd.bits.cmd.rs1(1)
        loop_being_configured.low_d := cmd.bits.cmd.rs1(2)
        loop_being_configured.act := cmd.bits.cmd.rs1(8+Activation.bitwidth-1, 8) // TODO magic numbers

        loop_being_configured.a_ex_spad_id := cmd.bits.cmd.rs1(19, 18)
        loop_being_configured.b_ex_spad_id := cmd.bits.cmd.rs1(17, 16) 
        //loop_being_configured.a_transpose := cmd.bits.cmd.rs2(0)
        //loop_being_configured.b_transpose := cmd.bits.cmd.rs2(1)
        is_scale := cmd.bits.cmd.rs1(32)
        loop_being_configured.a_dram_stride := cmd.bits.cmd.rs2

        loop_being_configured.configured := true.B

        loops_configured := loops_configured + 1.U
      }
    }
  }

  // Wire up request signals
  val ld_d_addr_start = RegInit(0.U(log2Up(max_acc_addr).W))
  val ex_c_addr_start = RegInit(0.U(log2Up(max_acc_addr).W))
  val st_c_addr_start = RegInit(0.U(log2Up(max_acc_addr).W))

  val loop_requesting_ldA_id = Mux(head_loop.lda_started, tail_loop_id, head_loop_id)
  val loop_requesting_ldA = loops(loop_requesting_ldA_id)
  ldA.io.req.bits.max_k := loop_requesting_ldA.max_k// Mux(is_resadd, loop_requesting_ldA.max_j, loop_requesting_ldA.max_k)
  ldA.io.req.bits.max_i := loop_requesting_ldA.max_i
  ldA.io.req.bits.pad_k := loop_requesting_ldA.pad_k //Mux(is_resadd, loop_requesting_ldA.pad_j, loop_requesting_ldA.pad_k)
  ldA.io.req.bits.pad_i := loop_requesting_ldA.pad_i
  ldA.io.req.bits.dram_addr := loop_requesting_ldA.a_dram_addr
  ldA.io.req.bits.dram_stride := loop_requesting_ldA.a_dram_stride
  //ldA.io.req.bits.transpose := loop_requesting_ldA.a_transpose
  ldA.io.req.bits.addr_start := Mux(loop_requesting_ldA.a_ex_spad_id === 0.U, loop_requesting_ldA.a_addr_start, (loop_requesting_ldA.a_ex_spad_id - 1.U) * (max_addr / concurrent_loops).U)
  ldA.io.req.bits.loop_id := loop_requesting_ldA_id
  //ldA.io.req.bits.is_scale := is_scale

  ldA.io.req.valid := !loop_requesting_ldA.lda_started && loop_requesting_ldA.configured

  when (ldA.io.req.fire) {
    loop_requesting_ldA.running := true.B
    loop_requesting_ldA.lda_started := true.B
  }

  val loop_requesting_ldB_id = Mux(head_loop.ldb_started, tail_loop_id, head_loop_id)
  val loop_requesting_ldB = loops(loop_requesting_ldB_id)
  //ldB.io.req.bits.max_j := loop_requesting_ldB.max_j
  ldB.io.req.bits.max_k := loop_requesting_ldB.max_k //Mux(is_resadd, loop_requesting_ldB.max_i, loop_requesting_ldB.max_k)
  //ldB.io.req.bits.pad_j := loop_requesting_ldB.pad_j
  ldB.io.req.bits.pad_k := loop_requesting_ldB.pad_k //Mux(is_resadd, loop_requesting_ldB.pad_i, loop_requesting_ldB.pad_k)
  ldB.io.req.bits.dram_addr := loop_requesting_ldB.b_dram_addr
  //ldB.io.req.bits.dram_stride := loop_requesting_ldB.b_dram_stride
  //ldB.io.req.bits.transpose := loop_requesting_ldB.b_transpose
  ldB.io.req.bits.addr_end := Mux(loop_requesting_ldB.b_ex_spad_id === 0.U, loop_requesting_ldB.b_addr_end, (loop_requesting_ldB.b_ex_spad_id) * (max_addr / concurrent_loops).U)
  ldB.io.req.bits.loop_id := loop_requesting_ldB_id
  ldB.io.req.bits.is_scale := is_scale

  ldB.io.req.valid := !loop_requesting_ldB.ldb_started && loop_requesting_ldB.configured

  when (ldB.io.req.fire) {
    loop_requesting_ldB.running := true.B
    loop_requesting_ldB.ldb_started := true.B
  }

  val loop_requesting_ex_id = Mux(head_loop.ex_started, tail_loop_id, head_loop_id)
  val loop_requesting_ex = loops(loop_requesting_ex_id)
  //ex.io.req.bits.max_j := loop_requesting_ex.max_j
  ex.io.req.bits.max_k := loop_requesting_ex.max_k
  ex.io.req.bits.max_i := loop_requesting_ex.max_i
  //ex.io.req.bits.pad_j := loop_requesting_ex.pad_j
  ex.io.req.bits.pad_k := loop_requesting_ex.pad_k
  ex.io.req.bits.pad_i := loop_requesting_ex.pad_i
  ex.io.req.bits.accumulate := loop_requesting_ex.ex_accumulate
  ex.io.req.bits.a_addr_start := Mux(loop_requesting_ex.a_ex_spad_id === 0.U, loop_requesting_ex.a_addr_start, (loop_requesting_ex.a_ex_spad_id - 1.U) * (max_addr / concurrent_loops).U)
  ex.io.req.bits.b_addr_end := Mux(loop_requesting_ex.b_ex_spad_id === 0.U, loop_requesting_ex.b_addr_end, (loop_requesting_ex.b_ex_spad_id) * (max_addr / concurrent_loops).U) 
  //ex.io.req.bits.a_tranpose := loop_requesting_ex.a_transpose
  //ex.io.req.bits.b_tranpose := loop_requesting_ex.b_transpose
  ex.io.req.bits.c_addr_start := ex_c_addr_start
  ex.io.req.bits.loop_id := loop_requesting_ex_id
  ex.io.req.bits.skip := is_scale

  ex.io.req.valid := !loop_requesting_ex.ex_started && loop_requesting_ex.lda_started &&
    loop_requesting_ex.ldb_started && loop_requesting_ex.ldd_started && loop_requesting_ex.configured 

  when (ex.io.req.fire) {
    loop_requesting_ex.running := true.B
    loop_requesting_ex.ex_started := true.B

    when (loop_requesting_ex.c_dram_addr =/= 0.U) {
      ex_c_addr_start := floorAdd(ex_c_addr_start, (max_acc_addr / concurrent_loops).U, max_acc_addr.U)
    }
  }

  val loop_requesting_ldD_id = Mux(head_loop.ldd_started, tail_loop_id, head_loop_id)
  val loop_requesting_ldD = loops(loop_requesting_ldD_id)
  //ldD.io.req.bits.max_j := loop_requesting_ldD.max_j
  ldD.io.req.bits.max_i := loop_requesting_ldD.max_i
  //ldD.io.req.bits.pad_j := loop_requesting_ldD.pad_j
  ldD.io.req.bits.pad_i := loop_requesting_ldD.pad_i
  ldD.io.req.bits.dram_addr := loop_requesting_ldD.d_dram_addr
  //ldD.io.req.bits.dram_stride := loop_requesting_ldD.d_dram_stride
  ldD.io.req.bits.low_d := loop_requesting_ldD.low_d
  ldD.io.req.bits.addr_start := ld_d_addr_start
  ldD.io.req.bits.loop_id := loop_requesting_ldD_id

  ldD.io.req.valid := !loop_requesting_ldD.ldd_started && loop_requesting_ldD.configured

  when (ldD.io.req.fire) {
    loop_requesting_ldD.running := true.B
    loop_requesting_ldD.ldd_started := true.B

    when (loop_requesting_ldD.c_dram_addr =/= 0.U) {
      ld_d_addr_start := floorAdd(ld_d_addr_start, (max_acc_addr / concurrent_loops).U, max_acc_addr.U)
    }
  }

  val loop_requesting_st_id = Mux(head_loop.st_started, tail_loop_id, head_loop_id)
  val loop_requesting_st = loops(loop_requesting_st_id)
  stC.io.req.bits.max_k := Mux(is_scale, loop_requesting_st.max_i, loop_requesting_st.max_k) //Mux(is_resadd, 1.U, loop_requesting_st.max_k)
  //stC.io.req.bits.max_j := loop_requesting_st.max_j
  stC.io.req.bits.max_i := Mux(is_scale, loop_requesting_st.max_k, loop_requesting_st.max_i)
  //stC.io.req.bits.pad_j := loop_requesting_st.pad_j
  stC.io.req.bits.pad_i := loop_requesting_st.pad_i //Mux(is_scale, loop_requesting_st.pad_k, loop_requesting_st.pad_i)
  stC.io.req.bits.dram_addr := loop_requesting_st.c_dram_addr
  //stC.io.req.bits.dram_stride := loop_requesting_st.c_dram_stride
  stC.io.req.bits.full_c := loop_requesting_st.full_c
  stC.io.req.bits.act := loop_requesting_st.act
  stC.io.req.bits.addr_start := st_c_addr_start
  stC.io.req.bits.loop_id := loop_requesting_st_id
  stC.io.req.bits.is_scale := is_scale


  stC.io.req.valid := !loop_requesting_st.st_started && loop_requesting_st.ex_started && loop_requesting_st.configured

  when (stC.io.req.fire) {
    loop_requesting_st.running := true.B
    loop_requesting_st.st_started := true.B

    when (loop_requesting_st.c_dram_addr =/= 0.U) {
      st_c_addr_start := floorAdd(st_c_addr_start, (max_acc_addr / concurrent_loops).U, max_acc_addr.U)
    }
  }

  when(is_scale){
    //ldA.io.req.bits.addr_start := loop_requesting_ldA.scale_addr_start
    ldB.io.req.bits.addr_end := loop_requesting_ldB.scale_addr_start
    stC.io.req.bits.addr_start := loop_requesting_st.scale_addr_start
    stC.io.req.valid := !loop_requesting_st.st_started && loop_requesting_st.configured
  }

 
  // Handle completed signals
  when (ldA.io.idle && loops(ldA.io.loop_id).running && loops(ldA.io.loop_id).lda_started) {
    loops(ldA.io.loop_id).lda_completed := true.B
  }

  when (ldB.io.idle && loops(ldB.io.loop_id).running && loops(ldB.io.loop_id).ldb_started) {
    loops(ldB.io.loop_id).ldb_completed := true.B
  }

  when (ex.io.idle && loops(ex.io.loop_id).running && loops(ex.io.loop_id).ex_started) {
    loops(ex.io.loop_id).ex_completed := true.B
  }

  when (ldD.io.idle && loops(ldD.io.loop_id).running && loops(ldD.io.loop_id).ldd_started) {
    loops(ldD.io.loop_id).ldd_completed := true.B
  }

  when (stC.io.idle && loops(stC.io.loop_id).running && loops(stC.io.loop_id).st_started) {
    loops(stC.io.loop_id).st_completed := true.B
  }

  when (head_loop.running && head_loop.all_completed()) {
    head_loop.reset()
    head_loop_id := ~head_loop_id
  }

  // Resets
  when (reset.asBool) {
    loops.zipWithIndex.foreach { case (l, i) =>
      l.reset()
      l.a_addr_start := (i * (max_addr / concurrent_loops)).U
      l.b_addr_end := ((i+1) * (max_addr / concurrent_loops)).U
      l.scale_addr_start := (i * (max_acc_addr / concurrent_loops)).U
    }
  }
}

object VegaLoopMatmul {
  def apply(in: DecoupledIO[GemminiCmd], ld_completed: UInt, st_completed: UInt, ex_completed: UInt, clock_enable: Bool,
            block_size: Int, coreMaxAddrBits: Int, rob_size: Int, max_lds: Int, max_exs: Int, max_sts: Int,
            max_addr: Int, max_acc_addr: Int, input_w: Int, acc_w: Int, dma_max_bytes: Int, has_vega_gate: Boolean,
            mvin_rs2_t: MvinRs2, preload_rs1_t: PreloadRs, preload_rs2_t: PreloadRs,
            compute_rs1_t: ComputeRs, compute_rs2_t: ComputeRs, mvout_rs2_t: MvoutRs2)
           (implicit p: Parameters): (DecoupledIO[GemminiCmd], Bool) = {
    val mod = Module(new VegaLoopMatmul(block_size, coreMaxAddrBits, rob_size, max_lds, max_exs, max_sts,
      max_addr, max_acc_addr, input_w, acc_w, dma_max_bytes, has_vega_gate,
      mvin_rs2_t, preload_rs1_t, preload_rs2_t, compute_rs1_t, compute_rs2_t, mvout_rs2_t))
    mod.io.in <> in
    mod.io.ld_completed := ld_completed
    mod.io.st_completed := st_completed
    mod.io.ex_completed := ex_completed
    mod.io.clock_enable := clock_enable
    (mod.io.out, mod.io.busy)
  }

  def castDramOffset(dram_offset: UInt): UInt = {
    // Cast dram offsets to 32 bits max
    dram_offset & "hFFFFFFFF".U
  }
}
