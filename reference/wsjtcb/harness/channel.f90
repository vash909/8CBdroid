! Adapter only: original WSJT-CB channel encoder and waveform routines are used.
program channel_reference
  implicit none
  character(len=77) :: line
  character(len=1024) :: path
  integer(kind=1) :: bits(77), codeword(174)
  integer :: tones(79), status, unit
  real :: wave(151680)
  complex :: cwave(151680)
  call get_command_argument(1, path)
  do
    read(*, '(A)', iostat=status) line
    if (status<0) exit
    if (status/=0 .or. verify(line, '01')/=0) stop 2
    read(line, '(77i1)') bits
    call encode174_91(bits, codeword)
    call get_ft8_tones_from_77bits(bits, tones)
    write(*, '(77i1,a,174i1,a,79i1)') bits, '|', codeword, '|', tones
    if (len_trim(path)>0) then
      call gen_ft8wave(tones, 79, 1920, 2.0, 12000.0, 1000.0, cwave, wave, 0, 151680)
      open(newunit=unit, file=trim(path), access='stream', form='unformatted', status='replace')
      write(unit) wave
      close(unit)
    endif
  enddo
end program channel_reference
