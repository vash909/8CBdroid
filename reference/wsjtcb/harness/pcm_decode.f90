! Test adapter: known-time/frequency Goertzel demodulation, original desktop LDPC/CRC/unpacker.
! This is not the desktop's candidate search, weak-signal demodulator or false-decode filter.
program pcm_decode_reference
  use packjt77var, only: unpack77var
  implicit none
  integer, parameter :: nsps=1920, total=180000
  integer(kind=1) :: header(44), mask(174), message77(77), codeword(174)
  integer(kind=2) :: pcm(total)
  real :: llr(174)
  real(kind=8) :: samples(total), powers(0:7), coefficient, q0, q1, q2, frequency, pi, leakage, outside
  integer :: tones(79), gray(0:7), sync(7), symbol, tone, j, k, position, harderrors, iteration, unit, start, lead
  character(len=1024) :: path, argument
  character(len=77) :: bits
  character(len=37) :: text
  logical :: success, acoustic
  call get_command_argument(4,argument)
  acoustic=trim(argument)=="acoustic"
  gray=[0,1,3,2,5,6,4,7]
  sync=[3,1,4,0,6,5,2]
  call get_command_argument(1,path)
  call get_command_argument(2,argument)
  read(argument,*) frequency
  lead=9600
  call get_command_argument(3,argument)
  if(len_trim(argument)>0) then
    read(argument,*) coefficient
    lead=nint(coefficient*12000)
  endif
  if(lead<0.or.lead+79*nsps>total) stop 6
  open(newunit=unit,file=trim(path),access='stream',form='unformatted',status='old')
  read(unit) header,pcm
  close(unit)
  samples=real(pcm,8)/32768.0_8
  if(maxval(abs(samples))<0.001_8) stop 2
  pi=acos(-1.0_8)
  position=0
  leakage=0
  do symbol=1,79
    start=lead+(symbol-1)*nsps
    do tone=0,7
      coefficient=2*cos(2*pi*(frequency+tone*6.25_8)/12000)
      q1=0; q2=0
      do j=1,nsps
        q0=samples(start+j)+coefficient*q1-q2
        q2=q1; q1=q0
      enddo
      powers(tone)=max(0.0_8,q1*q1+q2*q2-coefficient*q1*q2)
    enddo
    tones(symbol)=maxloc(powers,dim=1)-1
    do k=0,1
      coefficient=2*cos(2*pi*(frequency-100+243.75_8*k)/12000)
      q1=0; q2=0
      do j=1,nsps
        q0=samples(start+j)+coefficient*q1-q2
        q2=q1; q1=q0
      enddo
      outside=max(0.0_8,q1*q1+q2*q2-coefficient*q1*q2)
      leakage=max(leakage,outside/maxval(powers))
    enddo
    if(symbol<=7.or.(symbol>=37.and.symbol<=43).or.symbol>=73) cycle
    do k=2,0,-1
      position=position+1
      llr(position)=real(20*(maxval(powers(gray),mask=btest([(j,j=0,7)],k)) - &
           maxval(powers(gray),mask=.not.btest([(j,j=0,7)],k)))/maxval(powers))
    enddo
  enddo
  ! Clean generated fixtures require every sync hard decision. Physical audio
  ! also uses the full desktop candidate search; let LDPC/CRC qualify this
  ! supplied-time adapter while retaining all observed hard tones for reporting.
  if(.not.acoustic) then
    if(any(tones(1:7)/=sync).or.any(tones(37:43)/=sync).or.any(tones(73:79)/=sync)) stop 3
  endif
  mask=0
  call bpdecode174_91var(llr,mask,30,message77,codeword,harderrors,iteration)
  if(harderrors<0) stop 4
  write(bits,'(77i1)') message77
  call unpack77var(bits,1,text,success,1)
  if(.not.success) stop 5
  write(*,'(a,a,79i1,a,a,a,es15.7)') bits,'|',tones,'|',trim(text),'|',leakage
end program pcm_decode_reference
