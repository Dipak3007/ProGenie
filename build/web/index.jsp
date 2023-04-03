<%@page contentType="text/html" pageEncoding="UTF-8"%>
<%
    if(session.getAttribute("name")==null){
        response.sendRedirect("login.jsp");
    }
%>
<!DOCTYPE html>
<html lang="en">

<head>
  <meta charset="UTF-8">
  <meta http-equiv="X-UA-Compatible" content="IE=edge">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Home - ProGenie</title>
  <link rel="icon" href="assets/images/logo.png" type="image/x-icon">
  <link rel="stylesheet" href="assets/css/style.css">
  <link rel="stylesheet" href="assets/css/index.css">

  <!-- Vendor CSS Files -->
  <link href="assets/css/vendor/animate.css/animate.min.css" rel="stylesheet">
  <link href="assets/css/vendor/bootstrap/css/bootstrap.min.css" rel="stylesheet">
  <link href="assets/css/vendor/bootstrap-icons/bootstrap-icons.css" rel="stylesheet">
  <link href="assets/css/vendor/boxicons/css/boxicons.min.css" rel="stylesheet">
  <link href="assets/css/vendor/glightbox/css/glightbox.min.css" rel="stylesheet">
  <link href="assets/css/vendor/swiper/swiper-bundle.min.css" rel="stylesheet">
</head>

<body>
    <input  type="hidden" id="status" value="<%= request.getAttribute("status") %>">
  <header class="myhead" id="myhead">
    <a href="index.jsp" class="logo1">
      <h2 class="logo">ProGenie</h2>
      <img src="assets/images/logo.png" alt="logo">
    </a>
    <nav class="navigation">
      <a href="#myhead" class="scrollto">Home</a>
      <a href="#about" class="scrollto">About</a>
      <a href="#Services" class="scrollto">Services</a>
      <a href="#contact" class="scrollto">Contact</a>
      <button onclick="window.location.href='Logout';" class="btnLogin-popup"><%= session.getAttribute("name") %></button>
    </nav>
  </header>

  <div class="first">
    <div class="shift">
      <div id="slogan">
        <p><b>Your local hero for all your home needs.</b> </p>
      </div>
      <div class="location_search">
        <button type="button" class="button">
          <span id="button_text">Raipur &nbsp</span>
          <span id="button_icon"><ion-icon name="caret-down"></ion-icon></span>
        </button>
        <form>
          <span class="fas fa-search fa-2x"><ion-icon name="search"></ion-icon></span>
          <input type="text" placeholder="Search for services"></input>
        </form>
      </div>

      <div class="category_1">
        <a href="electrician.jsp">
          <form id="point" class=button_1 action="">
            <img src="assets/images/electrician.png">
            <p>Electrician</p>
          </form>
        </a>

        <a href="#">
          <form id="point" class=button_1 action="">
            <img src="assets/images/maid.png" alt="">
            <p>Maid</p>
          </form>
        </a>

        <a href="#">
          <form id="point" class=button_1 action="">
            <img src="assets/images/plumber.png">
            <p>Plumber</p>
          </form>
        </a>

        <a href="#">
          <form id="point" class=button_1 action="">
            <img src="assets/images/cooking.png">
            <p>Chef</p>
          </form>
        </a>

        <a href="#">
          <form id="point" class=button_1 action="">
            <img src="assets/images/maintenance.png">
            <p>AC Repair</p>
          </form>
        </a>
      </div>
    </div>
  </div>
  </div>

  <!-- --------New Category Launches------------ -->
  <div id="Services">
  <div class="row">
    <div class="col-md-12 col-sm-12 col-xs-12">
      <div class="section-headline services-head text-center">
        <h2>Our Services</h2>
      </div>
    </div>
  </div>
  <div class="category_launches_newCat">
    <p>New Category Launches</p>
    <div class="category_launches_content">

      <form class=button_2 action="">
        <input type="image" src="assets/images/excon.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Expert Counselling</p>
        <p class="offers">Advanced Skin Treatments</p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/polish.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Furniture Making, Upholstery & Polish</p>
        <p class="offers">Flat ₹100 off</p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/interior.jpg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Interior Design</p>
        <p class="offers">Best Interior Designer</p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/paint.jpg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Wall Painting</p>
        <p class="offers">Best Offers</p>
      </form>
    </div>
  </div>

  <!-- --------Home Repairs------------ -->
  <hr>

  <div class="category_launches_newCat">
    <p>Home Repairs</p>
    <div class="category_launches_content">

      <form class=button_2 action="">
        <input type="image" src="assets/images/polish.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Furniture Making, Upholstery & Polish</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/carpenter1.png" width="280" height="175"></input>
        <p style="margin:6px 0px;">Carpenters</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="electrician.jsp">
        <input type="image" src="assets/images/electric.png" width="280" height="175"></input>
        <p style="margin:6px 0px;">Electricians</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/plumbing.jpg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Plumbers</p>
        <p class="offers"></p>
      </form>
    </div>
  </div>

  <!-- --------Cleaning & Pest Control------------ -->
  <hr>

  <div class="category_launches_newCat">
    <p>Cleaning & Pest Control</p>
    <div class="category_launches_content">

      <form class=button_2 action="">
        <input type="image" src="assets/images/pest1.jpg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Sofa & Carpet Cleanig</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/pest2.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Cockroach, Ant & General Pest Control</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/pest3.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Bed Bugs Control</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/pest4.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Termite Control</p>
        <p class="offers"></p>
      </form>
    </div>
  </div>

  <!--------------------Repair------------------------>
  <hr>

  <div class="category_launches_newCat">
    <p>Servicing, Repair, Installation & Uninstallation</p>
    <div class="category_launches_content">

      <form class=button_2 action="">
        <input type="image" src="assets/images/appliance1.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Instant Vedio Consult</p>
        <p class="offers">Free & instant diagnosis</p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/acrepair.jpg" width="280" height="175"></input>
        <p style="margin:6px 0px;">AC Repair</p>
        <p class="offers">Upto 10% off</p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/homeapplianceRepair.png" width="280" height="175"></input>
        <p style="margin:6px 0px;">Home Appliance Repair</p>
        <p class="offers">Upto 25% off</p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/computerRepair.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Computer Repair</p>
        <p class="offers">Free Servicing</p>
      </form>
    </div>
  </div>

  <!-- --------ProGenie Luxury Experience------------ -->
  <hr>

  <div class="category_launches_newCat">
    <p>ProGenie Luxury Experience</p>
    <div class="category_launches_content">

      <form class=button_2 action="">
        <input type="image" src="assets/images/lu1.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Salon Luxe</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/lu2.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Salon For Men Royale</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/lu3.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Spa Luxe</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/lu4.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Massage Therapy For Men Royale</p>
        <p class="offers"></p>
      </form>
    </div>
  </div>

  <!--------------------Care services------------ -->
  <hr>

  <div class="category_launches_newCat">
    <p>Care Services</p>
    <div class="category_launches_content">

      <form class=button_2 action="">
        <input type="image" src="assets/images/childCare.jpg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Child Care</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/Elderly-Care.jpg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Elderly Care</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/petCare.jpg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Pet Care</p>
        <p class="offers"></p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/personalTrainer.jpg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Personal Fitness Trainer</p>
        <p class="offers">Starts at ₹999</p>
      </form>
    </div>
  </div>

  <!--------------------Salon, Spa and Massage services------------ -->
  <hr>

  <div class="category_launches_newCat">
    <p>Salon, Spa and Massage services</p>
    <div class="category_launches_content">

      <form class=button_2 action="">
        <input type="image" src="assets/images/wax.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Salon Prime</p>
        <p class="offers">Free Waxing</p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/lu2.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Salon For Men</p>
        <p class="offers">Flat ₹100 off</p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/spaW.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Spa For Women</p>
        <p class="offers">Free head massage</p>
      </form>

      <form class=button_2 action="">
        <input type="image" src="assets/images/massageM.jpeg" width="280" height="175"></input>
        <p style="margin:6px 0px;">Massage For Men</p>
        <p class="offers">Starts at ₹499</p>
      </form>
    </div>
  </div>
  <!-- ======= About Section ======= -->
  <div id="about" class="about-area area-padding">
    <div class="container">
      <div class="row">
        <div class="col-md-12 col-sm-12 col-xs-12">
          <div class="section-headline text-center">
            <h2>About ProGenie</h2>
          </div>
        </div>
      </div>
      <div class="row">
        <!-- single-well start-->
        <div class="col-md-6 col-sm-6 col-xs-12">
          <div class="well-left">
            <div class="single-well">
              <a href="#">
                <img src="assets/images/homeapplianceRepair.png" alt="">
              </a>
            </div>
          </div>
        </div>
        <!-- single-well end-->
        <div class="col-md-6 col-sm-6 col-xs-12">
          <div class="well-middle">
            <div class="single-well">
              <p>
                  At ProGenie, we are dedicated to providing the best possible experience for our clients. Our mission is to connect local service providers with customers in need of their help. Here are a few things that make us stand out:
              </p>
              <ul>
                <li>
                  <i class="bi bi-check"></i> We carefully vet our providers for quality and professionalism.
                </li>
                <li>
                  <i class="bi bi-check"></i> Our user-friendly platform makes it easy to schedule appointments.
                </li>
                <li>
                  <i class="bi bi-check"></i> We provide excellent customer service and support.
                </li>
                <li>
                  <i class="bi bi-check"></i> Our pricing is transparent and fair.
                </li>
                <li>
                  <i class="bi bi-check"></i> We are always improving our platform and services.
                </li>
              </ul>
            </div>
          </div>
        </div>
        <!-- End col-->
      </div>
    </div>
  </div><!-- End About Section -->

  <!-- ======= Contact Section ======= -->
  <div id="contact" class="contact-area">
    <div class="contact-inner area-padding">
        <div class="contact-overly"></div>
        <div class="container ">
            <div class="row">
                <div class="col-md-12 col-sm-12 col-xs-12">
                    <div class="section-headline text-center">
                        <h2>Contact us</h2>
                    </div>
                </div>
            </div>
            <div class="row">
                <!-- Start contact icon column -->
                <div class="col-md-4">
                    <div class="contact-icon text-center">
                        <div class="single-icon">
                            <i class="bi bi-phone"></i>
                            <p>
                                Call: +91 (0771) 1234-5678<br>
                                <span>Monday-Friday (9am-5pm)</span>
                            </p>
                        </div>
                    </div>
                </div>
                <!-- Start contact icon column -->
                <div class="col-md-4">
                    <div class="contact-icon text-center">
                        <div class="single-icon">
                            <i class="bi bi-envelope"></i>
                            <p>
                                Email: admin@progenie.com<br>
                                <span>Web: www.progenie.com</span>
                            </p>
                        </div>
                    </div>
                </div>
                <!-- Start contact icon column -->
                <div class="col-md-4">
                    <div class="contact-icon text-center">
                        <div class="single-icon">
                            <i class="bi bi-geo-alt"></i>
                            <p>
                                Location: G.E. Road, Raipur<br>
                                <span>Chhattisgarh 492001, India</span>
                            </p>
                        </div>
                    </div>
                </div>
            </div>
            <div class="row">

                <!-- Start Google Map -->
                <div class="col-md-6">
                    <!-- Start Map -->
                    <!-- <iframe src="https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d3718.549083148077!2d81.60284041490854!3d21.24972218587912!2m3!1f0!2f0!3f0!3m2!1i1024!2i768!4f13.1!3m3!1m2!1s0x3a28dde213f66723%3A0x21543965c50c43c7!2sNational%20Institute%20of%20Technology(NIT)%2C%20Raipur!5e0!3m2!1sen!2sin!4v1679464515372!5m2!1sen!2sin" width="100%" height="450" style="border:0;" allowfullscreen="" loading="lazy" referrerpolicy="no-referrer-when-downgrade"></iframe> -->
                    <iframe
                        src="https://www.google.com/maps/embed?pb=!1m18!1m12!1m3!1d3718.549083148077!2d81.60284041490854!3d21.24972218587912!2m3!1f0!2f0!3f0!3m2!1i1024!2i768!4f13.1!3m3!1m2!1s0x3a28dde213f66723%3A0x21543965c50c43c7!2sNational%20Institute%20of%20Technology(NIT)%2C%20Raipur!5e0!3m2!1sen!2sin!4v1679464515372!5m2!1sen!2sin"
                        width="100%" height="380" frameborder="0" style="border:0" allowfullscreen></iframe>
                    <!-- End Map -->
                </div>
                <!-- End Google Map -->

                <!-- Start  contact -->
                <div class="col-md-6">
                    <div class="form contact-form">
                        <!-- <form action="mailto:admin@progenie.com" method="post" enctype="text/plain"> -->
                        <form action="Contact" method="post" role="form" class="php-email-form">
                            <div class="form-group">
                                <input type="text" name="name" class="form-control" id="name"
                                    placeholder="Your Name" required>
                            </div>
                            <div class="form-group mt-3">
                                <input type="email" class="form-control" name="email" id="email"
                                    placeholder="Your Email" required>
                            </div>
                            <div class="form-group mt-3">
                                <input type="text" class="form-control" name="subject" id="subject"
                                    placeholder="Subject" required>
                            </div>
                            <div class="form-group mt-3">
                                <textarea class="form-control" name="message" rows="5" placeholder="Message"
                                    required></textarea>
                            </div>
                            <div class="my-3">
                                <div class="loading">Loading</div>
                                <div class="error-message"></div>
                                <div class="sent-message">Your message has been sent. Thank you!</div>
                            </div>
                            <div class="text-center"><button type="submit">Send Message</button></div>
                        </form>
                    </div>
                </div>
                <!-- End Left contact -->
            </div>
        </div>
    </div>
</div><!-- End Contact Section -->

<!-- ======= Footer ======= -->
<footer>
  <div class="footer-area">
      <div class="container">
          <div class="row" style="justify-content: space-between;">
              <div class="col-md-4">
                  <div class="footer-content">
                      <div class="footer-head">
                          <a href="#myhead" class="logo1 nav-link scrollto" style="justify-content: flex-start;padding-bottom: 18px;">
                              <h2 class="logo" style="padding: 0;margin: 0;">ProGenie</h2>
                              <img src="assets/images/logo.png" alt="logo">
                          </a>

                          <p>Our mission is to simplify your life by providing a reliable and convenient way to find and book home services in your area.</p>
                          <div class="footer-icons">
                              <ul>
                                  <li>
                                      <a href="#"><i class="bi bi-facebook"></i></a>
                                  </li>
                                  <li>
                                      <a href="#"><i class="bi bi-twitter"></i></a>
                                  </li>
                                  <li>
                                      <a href="#"><i class="bi bi-instagram"></i></a>
                                  </li>
                                  <li>
                                      <a href="#"><i class="bi bi-linkedin"></i></a>
                                  </li>
                              </ul>
                          </div>
                      </div>
                  </div>
              </div>
              <!-- end single footer -->
              <div class="col-md-4">
                  <div class="footer-content">
                      <div class="footer-head">
                          <h4>information</h4>
                          <p>
                              Thank you for choosing ProGenie for your home service needs. We look forward to providing you with a seamless and hassle-free experience.
                          </p>
                          <div class="footer-contacts">
                              <p><span>Tel:</span> +91 (0771) 1234-5678</p>
                              <p><span>Email:</span> admin@progenie.com</p>
                              <p><span>Working Hours:</span> 9am-5pm</p>
                          </div>
                      </div>
                  </div>
              </div>
          </div>
      </div>
  </div>
  <div class="footer-area-bottom">
      <div class="container">
          <div class="row">
              <div class="col-md-12 col-sm-12 col-xs-12">
                  <div class="copyright text-center">
                      <p>
                          &copy; Copyright <strong>ProGenie</strong>. All Rights Reserved
                      </p>
                  </div>
              </div>
          </div>
      </div>
  </div>
</footer><!-- End  Footer -->


  <script type="module" src="https://unpkg.com/ionicons@5.5.2/dist/ionicons/ionicons.esm.js"></script>
  <script nomodule src="https://unpkg.com/ionicons@5.5.2/dist/ionicons/ionicons.js"></script>
  <script src="https://unpkg.com/sweetalert/dist/sweetalert.min.js"></script>
        <link rel="stylesheet" href="alert/dist/sweetalert.css">
        <script type="text/javascript">
            var status=document.getElementById("status").value;
            if(status == "success"){
                swal("","Thanks for contacting us!","success");
            }
        </script>
</body>

</html>
